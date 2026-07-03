(ns richbot.core
  (:require [clojure.edn :as edn]
            [richbot.backtest :as bt]
            [richbot.binance :as binance]
            [richbot.dca :as dca]
            [richbot.dips :as dips]
            [richbot.live :as live]
            [richbot.paper :as paper]
            [richbot.report :as report]
            [richbot.research :as research]
            [richbot.stocks :as stocks]
            [richbot.stocks-research :as stocks-research]
            [richbot.strategy :as strat]
            [richbot.walkforward :as wf])
  (:gen-class))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(def base-config
  "Defaults. Capital, contribution, pay day and owned holdings are
  overridden by config.local.edn."
  {:symbol "BNBUSDC"
   :interval "4h"
   :candles 3000
   ;; High-risk mode: six-year research showed BNBUSDC sma-cross had
   ;; the highest OOS return among tested candidates, but it was rejected
   ;; by conservative rules because historical drawdown was above 50%.
   ;; Use only with capital you are willing to lose.
   :strategy {:id :sma-cross
              :params {:fast 20 :slow 100 :band 0.005}
              :adaptive true :train 1000}
   :risk {:max-drawdown 0.50 :fraction 0.95}
   ;; Live mode: hard cap on traded capital (quote asset). The bot will
   ;; never spend more than this, whatever the account holds.
   :live {:capital 100.0
          :max-drawdown 0.50
          :daily-loss 0.25
          :fraction 0.95
          :max-errors 5}
   ;; Portfolio (June 2026 research, 6y walk-forward, see
   ;; RESEARCH-REPORT.md): a BTC accumulation core that buys weekly
   ;; and buys harder below the ~200-day trend, plus two risky
   ;; trend-following slots — the two best out-of-sample candidates
   ;; (BNB sma-cross +1147% OOS Sharpe 1.32, SOL filtered-donchian
   ;; +493% OOS Sharpe 0.79). Both had ~46-54% historical drawdown:
   ;; risk capital only.
   :portfolio {:capital 100.0
               :slots [{:name :core-btc
                        :symbol "BTCUSDC"
                        :weight 0.50
                        ;; Smart DCA: base buy every 42x4h = weekly,
                        ;; 2x/3x/5x sized when price is 10/25/40%
                        ;; below the 1200x4h (~200d) SMA. Never
                        ;; sells. capital*weight/buys must stay
                        ;; above Binance's ~5 USDC min notional.
                        :strategy {:id :dca
                                   :params {:every 42
                                            :buys 12
                                            :trend 1200
                                            :tiers [[0.10 2.0]
                                                    [0.25 3.0]
                                                    [0.40 5.0]]}}}
                       {:name :risky-bnb
                        :symbol "BNBUSDC"
                        :weight 0.25
                        :strategy {:id :sma-cross
                                   :params {:fast 20
                                            :slow 100
                                            :band 0.005}
                                   :adaptive true :train 1000}
                        :max-drawdown 0.50
                        :daily-loss 0.25}
                       {:name :risky-sol
                        :symbol "SOLUSDC"
                        :weight 0.25
                        :strategy {:id :filtered-donchian
                                   :params {:entry 55 :exit 20
                                            :trend 200
                                            :min-atr 0.0
                                            :max-atr 0.08}
                                   :adaptive true :train 1000}
                        :max-drawdown 0.50
                        :daily-loss 0.25}]}
   ;; Stocks advisor (June 2026 research, 15y walk-forward, see
   ;; STOCKS.md): no broker API - it sends BUY/SELL recommendations
   ;; by Telegram and the user executes them manually in Revolut.
   ;; :capital is the first month's money; :contribution is added
   ;; to the model once a month on the first tick on/after :pay-day
   ;; (set it just after your payday), with a Telegram reminder to
   ;; fund the broker. Risky slots deliberately avoid names already
   ;; held elsewhere (set them in :dips :owned via config.local.edn):
   ;; COST and TSLA diversify sector exposure (June 2026
   ;; diversification research).
   :stocks {:capital 1000.0
            :contribution 500.0
            :pay-day 1
            :currency "EUR"
            :poll-ms 600000
            :slots [{:name :core-etf
                     :label "fondo indexado mundial Vanguard (90% Global Stock + 10% EM)"
                     ;; VWCE tracks the same all-world exposure;
                     ;; its price series is the signal proxy for
                     ;; the fund combo.
                     :yahoo "VWCE.DE"
                     :venue "MyInvestor"
                     :currency "EUR"
                     :weight 0.60
                     ;; Monthly base buy every ~21 trading days as
                     ;; a fund contribution (free, no trade limit).
                     ;; With recurring contributions, holding back
                     ;; a dip reserve LOST 7-16% over 20y of SPY,
                     ;; so the full base is invested every month.
                     ;; Dips instead trigger :recharge - optional
                     ;; EXTRA deposits (20y SPY: buying 5/10/15%
                     ;; below the SMA200 averaged +15/+27/+36% one
                     ;; year later), at most one per ~month.
                     ;; the base buy is :contribution * :weight,
                     ;; derived at runtime
                     :strategy {:id :dca
                                :params {:every 21
                                         :trend 200
                                         :tiers [[0.05 1.5]
                                                 [0.15 2.5]
                                                 [0.30 4.0]]
                                         :recharge
                                         {:cooldown 21
                                          :tiers [[0.05 300.0]
                                                  [0.10 600.0]
                                                  [0.15 1200.0]]}}}}
                    {:name :risky-cost
                     ;; +223% OOS, Sharpe 0.81, maxDD 16.5% - the
                     ;; best risk-adjusted candidate; consumer
                     ;; retail, not in the user's tech-heavy book.
                     :label "Costco (COST)"
                     :yahoo "COST"
                     :currency "USD"
                     :weight 0.20
                     :strategy {:id :donchian
                                :params {:entry 40 :exit 20}
                                :adaptive true :train 504}
                     :max-drawdown 0.30
                     :daily-loss 0.20}
                    {:name :risky-tsla
                     ;; +499% OOS, Sharpe 0.63, maxDD 52.9% - the
                     ;; high-octane slot; own sector, not held.
                     :label "Tesla (TSLA)"
                     :yahoo "TSLA"
                     :currency "USD"
                     :weight 0.20
                     :strategy {:id :sma-cross
                                :params {:fast 20 :slow 100
                                         :band 0.005}
                                :adaptive true :train 504}
                     :max-drawdown 0.55
                     :daily-loss 0.20}]}
   :dips {:tiers [0.05 0.10 0.15 0.20]
          :opportunity-budget 300.0
          :buy-tiers [[0.05 150.0]
                      [0.09 300.0]
                      [0.15 450.0]]
          ;; holdings come from the broker CSV (REVOLUT_STOCKS_CSV)
          ;; or from config.local.edn
          :owned #{}
          :crowded-tags #{:mega-tech :ai :semis}}})

(def config
  "base-config overridden by config.local.edn when present."
  (deep-merge base-config
              (or (try (edn/read-string (slurp "config.local.edn"))
                       (catch Exception _ nil))
                  {})))

(def wf-presets
  "Walk-forward data sizes per interval: ~2-3 years of history,
  training windows long enough for slow indicators to warm up."
  {"1h" {:candles 17520 :train 2000 :test 500}
   "4h" {:candles 6570 :train 1000 :test 250}
   "1d" {:candles 1095 :train 365 :test 90}})

(defn- pct [x] (format "%.2f%%" (* 100.0 x)))
(defn- fmt [x] (if x (format "%.2f" (double x)) "n/a"))

(defn backtest! [{:keys [symbol interval candles strategy]}]
  (let [cs (binance/klines-history! symbol interval candles)
        signals (strat/signals strategy cs)
        ppy (binance/periods-per-year interval)
        {:keys [stats]} (bt/run cs signals {:periods-per-year ppy})]
    (println "Backtest" symbol interval "-" (count cs) "candles")
    (println "Strategy:"      (:id strategy) (:params strategy))
    (println "Final equity: " (fmt (:final-equity stats)))
    (println "Return:       " (pct (:return stats)))
    (println "Buy & hold:   " (pct (:buy-hold-return stats)))
    (println "Round trips:  " (:round-trips stats))
    (println "Win rate:     " (or (some-> (:win-rate stats) pct) "n/a"))
    (println "Profit factor:" (fmt (:profit-factor stats)))
    (println "Sharpe:       " (fmt (:sharpe stats)))
    (println "Exposure:     " (pct (:exposure stats)))
    (println "Max drawdown: " (pct (:max-drawdown stats)))))

(defn walkforward! [{:keys [symbol]} interval]
  (if-let [{:keys [candles train test]} (wf-presets interval)]
    (let [cs (binance/klines-history! symbol interval candles)
          ppy (binance/periods-per-year interval)]
      (println "Walk-forward" symbol interval "-" (count cs) "candles")
      (wf/report! cs {:train train :test test
                      :opts {:periods-per-year ppy}}))
    (println "unknown interval" interval
             "- use one of" (keys wf-presets))))

(defn research! [interval years symbols]
  (research/report! {:interval (or interval "4h")
                     :years (if years (parse-double years) 6.0)
                     :symbols symbols}))

(defn portfolio! [{:keys [portfolio]}]
  (let [{:keys [capital slots]} portfolio]
    (println "Portfolio plan | capital" capital)
    (doseq [{slot-name :name
             :keys [symbol weight strategy max-drawdown]} slots]
      (println (format "%s %s weight %.2f%% capital %.2f strategy %s %s max-dd %s"
                       (name slot-name) symbol (* 100.0 weight)
                       (* capital weight)
                       (name (:id strategy)) (:params strategy)
                       (if max-drawdown
                         (format "%.2f%%" (* 100.0 max-drawdown))
                         "n/a (accumulation, never sells)"))))))

(defn dca! [interval years]
  (dca/report! {:symbol "BTCUSDC"
                :interval (or interval "1d")
                :years (if years (parse-double years) 6.0)}))

(defn -main [& [cmd arg sym extra]]
  (let [cfg (cond-> config sym (assoc :symbol sym))]
    (case (or cmd "backtest")
      "backtest" (backtest! cfg)
      "paper" (paper/start! cfg)
      "live" (live/start! cfg (= arg "yes"))
      "portfolio-live" (live/start-portfolio! cfg (= arg "yes"))
      "walkforward" (walkforward! cfg (or arg "1h"))
      "research" (research! (or arg "4h") sym extra)
      "dca" (dca! arg sym)
      "portfolio" (portfolio! cfg)
      "stocks" (stocks/plan! config)
      "stocks-advisor" (stocks/start! config)
      "stocks-research" (stocks-research/report! {})
      "stocks-dca" (stocks-research/dca-report! {})
      "stocks-dips" (dips/report! (:dips config))
      "stocks-dips-scan" (dips/run-scan! (:dips config))
      "stocks-dips-status" (dips/status! (:dips config))
      "stocks-tax" (dips/tax-report! (:dips config))
      "stocks-dips-backtest" (dips/backtest! {})
      "stocks-dips-fundamentals-template"
      (dips/fundamentals-template! (:dips config))
      "report" (report/weekly! config)
      (println "usage: clojure -M -m richbot.core"
               "[backtest|paper|live|walkforward [1h|4h|1d] [SYMBOL]]"
               "or research [1h|4h|1d] [YEARS] [SYMBOLS_CSV]"
               "or dca [1d|4h] [YEARS]"
               "or portfolio|portfolio-live"
               "or stocks|stocks-advisor|stocks-research|stocks-dca"
               "|stocks-dips|stocks-dips-scan"
               "|stocks-dips-status|stocks-dips-backtest|stocks-tax"
               "|stocks-dips-fundamentals-template"))))

(comment
  (dips/scan! (:dips config) "2026-06-11" {})
  ) 
