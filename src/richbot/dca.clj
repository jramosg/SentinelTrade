(ns richbot.dca
  "Smart dollar-cost averaging for a core asset (BTC): a base-size
  buy on a fixed schedule, scaled up when price trades at a discount
  to its long trend SMA. Accumulation only — it never sells, so the
  drawdown kill switch does not apply. The slot budget is split into
  base-size buys; dips draw the remaining budget down faster."
  (:require [richbot.backtest :as bt]
            [richbot.binance :as binance]
            [richbot.indicators :as ind]))

(def default-params
  "4h-candle defaults: weekly base buy, ~200-day trend SMA.
  Tiers are [min-discount multiplier]: at >=10% below trend buy 2x,
  >=25% below 3x, >=40% below 5x."
  {:every 42
   :buys 12
   :trend 1200
   :tiers [[0.10 2.0] [0.25 3.0] [0.40 5.0]]})

(def presets
  "Schedule/trend presets per candle interval (both ~weekly buys
  against a ~200-day SMA)."
  {"1d" {:every 7 :trend 200}
   "4h" {:every 42 :trend 1200}})

(defn multiplier
  "Buy-size multiplier for a discount (1 - price/sma) given tiers
  [[min-discount mult] ...]. 1.0 (the plain scheduled buy) when
  price is at or above trend, or during SMA warmup."
  [tiers discount]
  (if discount
    (reduce (fn [m [d x]] (if (>= discount d) (max m x) m))
            1.0 tiers)
    1.0))

(defn decide
  "Pure DCA decision from closed candles and slot state. Returns
  {:price :discount :mult :due? :base :latest :spend}. :spend is
  0.0 when no scheduled buy is due or the budget is exhausted."
  [{:keys [every buys trend tiers base-amount]} candles
   {:keys [cash base last-buy]}]
  (let [closes (map :close candles)
        sma (last (ind/sma trend closes))
        price (last closes)
        latest (:open-time (last candles))
        ;; Count closed candles since the last buy instead of time
        ;; arithmetic: daily stock candles skip weekends, so candle
        ;; spacing is not constant.
        since (if last-buy
                (count (filter #(> (:open-time %) last-buy) candles))
                every)
        due? (>= since every)
        discount (some->> sma (/ price) (- 1.0))
        mult (multiplier tiers discount)
        ;; :base-amount (recurring-contribution mode) overrides the
        ;; fixed-budget split.
        base (or base-amount base (/ cash buys))]
    {:price price :discount discount :mult mult :due? due?
     :base base :latest latest
     :spend (if due? (min cash (* base mult)) 0.0)}))

(defn backtest
  "Simulate DCA over candles: a base-size buy every :every candles,
  scaled by the trend-discount multiplier, until the budget runs
  out. Equity (idle cash + position) is marked on every candle."
  [candles {:keys [every trend tiers]}
   {:keys [cash fee slippage periods-per-year]
    :or {cash 10000.0 fee 0.001 slippage 0.0005
         periods-per-year 365.25}}]
  (let [closes (mapv :close candles)
        smas (vec (ind/sma trend closes))
        n (count closes)
        base (/ cash (inc (quot (dec n) every)))]
    (loop [i 0 c cash qty 0.0 spent 0.0 equity (transient [])]
      (if (= i n)
        (let [equity (persistent! equity)]
          {:qty qty
           :invested spent
           :avg-cost (when (pos? qty) (/ spent qty))
           :equity equity
           :stats {:final-equity (peek equity)
                   :return (dec (/ (peek equity) cash))
                   :buy-hold-return (dec (/ (peek closes) (closes 0)))
                   :sharpe (bt/sharpe equity periods-per-year)
                   :max-drawdown (bt/max-drawdown equity)}})
        (let [price (closes i)
              spend (if (and (pos? c) (zero? (mod i every)))
                      (let [d (some->> (smas i) (/ price) (- 1.0))]
                        (min c (* base (multiplier tiers d))))
                      0.0)
              got (if (pos? spend)
                    (/ (* spend (- 1.0 fee))
                       (* price (+ 1.0 slippage)))
                    0.0)
              c (- c spend)
              qty (+ qty got)]
          (recur (inc i) c qty (+ spent spend)
                 (conj! equity (+ c (* qty price)))))))))

(defn- pct [x] (format "%.2f%%" (* 100.0 (double x))))
(defn- fmt [x] (if x (format "%.2f" (double x)) "n/a"))

(defn report!
  "Backtest smart DCA vs plain DCA vs lump sum on history fetched
  from Binance and print the comparison."
  [{:keys [symbol interval years]
    :or {symbol "BTCUSDC" interval "1d" years 6.0}}]
  (let [n (long (* (binance/periods-per-year interval) years))
        candles (binance/klines-history! symbol interval n)
        opts {:periods-per-year (binance/periods-per-year interval)}
        params (merge default-params (presets interval))
        variants [["smart-dca" params]
                  ["plain-dca" (assoc params :tiers [])]
                  ["lump-sum" (assoc params :every (count candles)
                                     :tiers [])]]]
    (println "DCA backtest" symbol interval "-" (count candles)
             "candles | base buy every" (:every params)
             "candles | trend SMA" (:trend params)
             "| tiers" (:tiers params))
    (println "variant,invested,qty,avg-cost,final-equity,return,"
             "sharpe,max-dd")
    (doseq [[label p] variants
            :let [{:keys [invested qty avg-cost stats]}
                  (backtest candles p opts)]]
      (println (format "%s,%.0f,%.6f,%.0f,%.0f,%s,%s,%s"
                       label invested qty (or avg-cost 0.0)
                       (:final-equity stats)
                       (pct (:return stats))
                       (fmt (:sharpe stats))
                       (pct (:max-drawdown stats)))))
    (println "buy & hold over span:"
             (pct (:buy-hold-return
                   (:stats (backtest candles
                                     (assoc params :every n :tiers [])
                                     opts)))))))
