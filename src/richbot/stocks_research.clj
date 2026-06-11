(ns richbot.stocks-research
  "Research for the stocks module: smart-DCA backtests on index
  ETFs and rolling walk-forward validation of the strategy registry
  on individual stocks. Evidence generation, not a profit promise."
  (:require [clojure.string :as str]
            [richbot.dca :as dca]
            [richbot.stocks-data :as sd]
            [richbot.strategy :as strat]
            [richbot.walkforward :as wf]))

(def dca-symbols ["SPY" "VWCE.DE"])

(def risky-symbols
  "Broad review universe: mega-tech plus the diversification names
  (pharma, finance, consumer, energy, EU listings) so quarterly
  re-research compares current slots against everything."
  ["NVDA" "TSLA" "AAPL" "AMD" "META" "GOOGL" "AMZN" "MSFT" "NFLX"
   "COST" "LLY" "UNH" "JPM" "XOM" "ASML" "NVO" "ITX.MC" "MC.PA"])

(def risky-rules
  "Relaxed risk sleeve rules (mirrors the crypto risky slots)."
  {:min-return 0.0
   :min-sharpe 0.35
   :max-drawdown 0.55
   :max-exposure 0.85})

(def dca-params
  "Stock-market DCA: weekly base buy (5 trading days), 200-day
  trend SMA. Tier variants compared by the report."
  {:every 5 :buys 12 :trend 200})

(def tier-variants
  {"deep (10/25/40 -> 2/3/5x)" [[0.10 2.0] [0.25 3.0] [0.40 5.0]]
   "shallow (5/15/30 -> 1.5/2.5/4x)" [[0.05 1.5] [0.15 2.5] [0.30 4.0]]
   "plain" []})

(defn- pct [x] (format "%.2f%%" (* 100.0 (double x))))
(defn- fmt [x] (if x (format "%.2f" (double x)) "n/a"))

(defn dca-report!
  "Compare DCA tier variants (and lump sum) on index ETFs."
  [{:keys [symbols years] :or {symbols dca-symbols years 20}}]
  (let [opts {:periods-per-year sd/periods-per-year}]
    (doseq [symbol symbols]
      (let [candles (sd/daily-candles! symbol years)]
        (println)
        (println "DCA" symbol "-" (count candles)
                 "daily candles (dividends reinvested)")
        (println "variant,invested,qty,avg-cost,final-equity,"
                 "return,sharpe,max-dd")
        (doseq [[label tiers] (sort-by key tier-variants)
                :let [{:keys [invested qty avg-cost stats]}
                      (dca/backtest candles
                                    (assoc dca-params :tiers tiers)
                                    opts)]]
          (println (format "%s,%.0f,%.4f,%.2f,%.0f,%s,%s,%s"
                           label invested qty (or avg-cost 0.0)
                           (:final-equity stats)
                           (pct (:return stats))
                           (fmt (:sharpe stats))
                           (pct (:max-drawdown stats)))))
        (println "lump-sum:"
                 (pct (:return
                       (:stats (dca/backtest
                                candles
                                (assoc dca-params
                                       :every (count candles)
                                       :tiers [])
                                opts)))))))))

(defn- reject-reasons
  [{:keys [return sharpe max-drawdown exposure]} rules]
  (cond-> []
    (< return (:min-return rules)) (conj "negative-return")
    (or (nil? sharpe)
        (< sharpe (:min-sharpe rules))) (conj "low-sharpe")
    (> max-drawdown (:max-drawdown rules)) (conj "high-drawdown")
    (> exposure (:max-exposure rules)) (conj "overexposed")))

(defn risky-report!
  "Walk-forward every registered strategy over individual stocks.
  train 504 / test 126 daily candles (~2y train, ~6mo test)."
  [{:keys [symbols years train test rules]
    :or {symbols risky-symbols years 15
         train 504 test 126 rules risky-rules}}]
  (let [opts {:periods-per-year sd/periods-per-year}
        rows
        (vec
         (for [symbol symbols
               :let [candles (try (sd/daily-candles! symbol years)
                                  (catch Exception e
                                    (println "SKIP" symbol "-"
                                             (ex-message e))
                                    nil))]
               :when (and candles
                          (>= (count candles) (+ train test)))
               id (keys strat/strategies)
               :let [{:keys [stats windows]}
                     (wf/evaluate-strategy id candles
                                           {:train train
                                            :test test
                                            :opts opts})]]
           {:symbol symbol :strategy id :windows windows
            :stats stats
            :reasons (reject-reasons stats rules)}))]
    (println "symbol,strategy,windows,oos-return,buy-hold,sharpe,"
             "max-dd,exposure,status")
    (doseq [{:keys [symbol strategy windows stats reasons]}
            (sort-by (juxt :symbol :strategy) rows)]
      (println (str symbol "," (name strategy) "," windows ","
                    (pct (:return stats)) ","
                    (pct (:buy-hold-return stats)) ","
                    (fmt (:sharpe stats)) ","
                    (pct (:max-drawdown stats)) ","
                    (pct (:exposure stats)) ","
                    (if (empty? reasons)
                      "ACCEPT"
                      (str "REJECT:" (str/join "|" reasons))))))
    (println)
    (println "Rules:" rules)
    rows))

(defn report! [args]
  (dca-report! args)
  (println)
  (risky-report! args))
