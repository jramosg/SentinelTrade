(ns richbot.research
  "Multi-symbol, multi-strategy research reports.
  This is evidence generation, not a profit guarantee."
  (:require [clojure.string :as str]
            [richbot.binance :as binance]
            [richbot.strategy :as strat]
            [richbot.walkforward :as wf]))

(def default-symbols
  ["BTCUSDC" "ETHUSDC" "SOLUSDC" "BNBUSDC"])

(def default-rules
  {:min-return 0.0
   :min-sharpe 0.35
   :max-drawdown 0.45
   :max-exposure 0.85
   :max-weight 0.25})

(defn- pct [x]
  (format "%.2f%%" (* 100.0 (double x))))

(defn- fmt [x]
  (if x (format "%.2f" (double x)) "n/a"))

(defn- parse-symbols [s]
  (if (str/blank? (or s ""))
    default-symbols
    (str/split s #",")))

(defn- candles-for-years [interval years]
  (long (* (binance/periods-per-year interval) years)))

(defn- reject-reasons
  [{:keys [return sharpe max-drawdown exposure]} rules]
  (cond-> []
    (< return (:min-return rules))
    (conj "negative-return")

    (or (nil? sharpe) (< sharpe (:min-sharpe rules)))
    (conj "low-sharpe")

    (> max-drawdown (:max-drawdown rules))
    (conj "high-drawdown")

    (> exposure (:max-exposure rules))
    (conj "overexposed")))

(defn- score [{:keys [return sharpe max-drawdown]}]
  (* (max 0.0 return)
     (max 0.0 (or sharpe 0.0))
     (- 1.0 max-drawdown)))

(defn- normalize-capped [rows max-weight]
  (loop [open rows
         done []]
    (let [total (reduce + (map :score open))]
      (if (or (empty? open) (not (pos? total)))
        done
        (let [{capped true uncapped false}
              (group-by #(> (/ (:score %) total) max-weight) open)
              capped (map #(assoc % :weight max-weight) capped)
              used (reduce + (map :weight capped))
              budget (- 1.0 (reduce + (map :weight done)) used)]
          (if (empty? capped)
            (into done
                  (map #(assoc % :weight (* budget (/ (:score %) total)))
                       open))
            (recur uncapped (into done capped))))))))

(defn- evaluate-symbol!
  [symbol interval years train test opts]
  (println)
  (println "Fetching" symbol interval years "years...")
  (try
    (let [candles (binance/klines-history!
                   symbol interval (candles-for-years interval years))]
      (println " " (count candles) "candles")
      (mapv (fn [id]
              (let [{:keys [stats windows]}
                    (wf/evaluate-strategy id candles
                                          {:train train
                                           :test test
                                           :opts opts})]
                {:symbol symbol
                 :strategy id
                 :windows windows
                 :stats stats
                 :score (score stats)}))
            (keys strat/strategies)))
    (catch Exception e
      (println " SKIP" symbol "-" (ex-message e))
      [])))

(defn- print-row [{:keys [symbol strategy windows stats accepted? reasons]}]
  (println (str symbol ","
                (name strategy) ","
                windows ","
                (pct (:return stats)) ","
                (pct (:buy-hold-return stats)) ","
                (fmt (:sharpe stats)) ","
                (pct (:max-drawdown stats)) ","
                (pct (:exposure stats)) ","
                (if accepted? "ACCEPT" (str "REJECT:"
                                            (str/join "|" reasons))))))

(defn report!
  "Run a multi-symbol walk-forward research report.
  Usage defaults: 4h, 6 years, major USDC pairs."
  [{:keys [symbols interval years train test rules]
    :or {interval "4h" years 6.0 train 1000 test 250}}]
  (let [symbols (parse-symbols symbols)
        rules (merge default-rules rules)
        opts {:periods-per-year (binance/periods-per-year interval)}
        rows (->> symbols
                  (mapcat #(evaluate-symbol! %
                                             interval years train test opts))
                  vec)
        rows (mapv (fn [row]
                     (let [reasons (reject-reasons (:stats row) rules)]
                       (assoc row
                              :accepted? (empty? reasons)
                              :reasons reasons)))
                   rows)
        accepted (->> rows
                      (filter :accepted?)
                      (sort-by :score >)
                      vec)
        allocation (normalize-capped accepted (:max-weight rules))]
    (println)
    (println "symbol,strategy,windows,oos-return,buy-hold,sharpe,"
             "max-dd,exposure,status")
    (doseq [row (sort-by (juxt :symbol :strategy) rows)]
      (print-row row))
    (println)
    (println "ACCEPTED allocation recommendation")
    (if (seq allocation)
      (doseq [{:keys [symbol strategy weight stats]} allocation]
        (println (str symbol "," (name strategy)
                      ",weight," (pct weight)
                      ",oos," (pct (:return stats))
                      ",max-dd," (pct (:max-drawdown stats))
                      ",sharpe," (fmt (:sharpe stats)))))
      (println "none - keep capital in USDC until evidence improves"))
    (println)
    (println "Rules:" rules)
    {:rows rows :allocation allocation :rules rules}))
