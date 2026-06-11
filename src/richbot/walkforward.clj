(ns richbot.walkforward
  "Rolling walk-forward validation: optimize strategy params on a
  training window, then evaluate on the following unseen window.
  Only out-of-sample results count — this is the overfitting guard."
  (:require [richbot.backtest :as bt]
            [richbot.strategy :as strat]))

(defn- pct [x] (format "%.2f%%" (* 100.0 x)))
(defn- fmt [x] (if x (format "%.2f" (double x)) "n/a"))

(defn- expand-grid [grid valid?]
  (->> (reduce (fn [ms [k vs]]
                 (for [m ms, v vs] (assoc m k v)))
               [{}]
               grid)
       (filter (or valid? (constantly true)))))

(defn- score
  "In-sample fitness: Sharpe when defined (more robust to overfit
  than raw return), otherwise return."
  [stats]
  (or (:sharpe stats) (:return stats) ##-Inf))

(defn best-params
  "Param combo with the best in-sample score on candles."
  [signals-fn grid valid? candles opts]
  (apply max-key
         #(score (:stats (bt/run candles (signals-fn % candles) opts)))
         (expand-grid grid valid?)))

(defn pick-params
  "Best params for strategy id on trailing candles, scored the same
  way as the walk-forward training phase. Used by the live trader
  for adaptive re-optimization."
  [id candles opts]
  (let [{:keys [signals grid valid?]} (strat/strategies id)]
    (best-params signals grid valid? candles opts)))

(defn windows [n train test]
  (for [start (range 0 (inc (- n train test)) test)]
    {:train-start start
     :test-start (+ start train)
     :test-end (+ start train test)}))

(defn oos-run
  "Evaluate params on the test slice, computing indicators with
  warmup data from the preceding training window."
  [signals-fn params candles
   {:keys [train-start test-start test-end]} opts]
  (let [slice (subvec candles train-start test-end)
        sigs (vec (take (count slice) (signals-fn params slice)))
        sigs (into sigs (repeat (- (count slice) (count sigs)) nil))
        offset (- test-start train-start)]
    (bt/run (subvec slice offset) (subvec sigs offset) opts)))

(defn stitch
  "Concatenate per-window equity curves into one growth curve,
  scaling each window to continue from the previous level."
  [cash equities]
  (reduce (fn [acc eq]
            (let [scale (/ (peek acc) cash)]
              (into acc (map #(* scale %) eq))))
          [cash]
          equities))

(defn evaluate-strategy
  "Walk-forward one strategy and return aggregate out-of-sample stats."
  [id candles {:keys [train test opts] :or {train 2000 test 500 opts {}}}]
  (let [{:keys [signals grid valid?]} (strat/strategies id)
        cash (:cash bt/default-opts)
        ppy (:periods-per-year opts
                               (:periods-per-year bt/default-opts))
        ws (windows (count candles) train test)
        results
        (mapv (fn [{:keys [train-start test-start] :as w}]
                (let [tcs (subvec candles train-start test-start)
                      params (best-params signals grid valid? tcs opts)
                      oos (oos-run signals params candles w opts)]
                  {:params params
                   :stats (:stats oos)
                   :equity (:equity oos)}))
              ws)
        stitched (stitch cash (map :equity results))
        total-bh (dec (reduce * (map #(inc (:buy-hold-return
                                            (:stats %)))
                                     results)))
        expo (/ (reduce + (map #(:exposure (:stats %)) results))
                (max 1 (count results)))
        span (subvec candles train (:test-end (last ws)))]
    {:id id
     :windows (count ws)
     :window-results results
     :equity stitched
     :stats {:return (dec (/ (peek stitched) cash))
             :buy-hold-return total-bh
             :sharpe (bt/sharpe stitched ppy)
             :max-drawdown (bt/max-drawdown stitched)
             :buy-hold-max-drawdown (bt/max-drawdown (mapv :close span))
             :exposure expo}}))

(defn report!
  "Walk-forward every registered strategy over candles and print
  per-window picks plus aggregate out-of-sample risk stats."
  [candles {:keys [train test opts] :or {train 2000 test 500 opts {}}}]
  (let [ws (windows (count candles) train test)
        span (subvec candles train (:test-end (last ws)))
        bh-dd (bt/max-drawdown (mapv :close span))]
    (doseq [id (keys strat/strategies)]
      (let [{:keys [window-results stats]}
            (evaluate-strategy id candles
                               {:train train :test test :opts opts})]
        (println)
        (println (name id) "-" (count ws) "windows,"
                 "train" train "test" test)
        (doseq [{:keys [params stats]} window-results]
          (println " " params "oos" (pct (:return stats))
                   "b&h" (pct (:buy-hold-return stats))))
        (println "  TOTAL oos:" (pct (:return stats))
                 "| sharpe" (fmt (:sharpe stats))
                 "| maxDD" (pct (:max-drawdown stats))
                 "| exposure" (pct (:exposure stats)))
        (println "  buy&hold: " (pct (:buy-hold-return stats))
                 "| maxDD" (pct bh-dd))))))
