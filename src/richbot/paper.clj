(ns richbot.paper
  "Trading loop against the Binance testnet: real signed orders on
  testnet.binance.vision, no real money. Includes position sizing,
  a max-drawdown kill switch persisted across restarts, adaptive
  re-optimization of params, exchange filters and a CSV trade log.
  Network or API errors are logged and retried, never fatal."
  (:require [clojure.edn :as edn]
            [richbot.binance :as binance]
            [richbot.strategy :as strat]
            [richbot.tradelog :as tlog]
            [richbot.walkforward :as wf])
  (:import [java.time Instant]))

(def ^:private state-file ".richbot-state.edn")
(def ^:private csv-file "trades-testnet.csv")
(def ^:private equity-file "equity-testnet.csv")

(defn- now [] (str (Instant/now)))

(defn- load-state []
  (try (edn/read-string (slurp state-file))
       (catch Exception _ nil)))

(defn- save-state! [state]
  (spit state-file (pr-str state)))

(defn- log+print! [side resp symbol quote]
  (println (now) side (:executedQty resp) symbol
           "for" (:cummulativeQuoteQty resp) quote)
  (tlog/append! csv-file
                {:venue :testnet :symbol symbol :side side
                 :resp resp}))

(defn- mark-equity! [symbol price cash base-qty equity peak signal]
  (tlog/append-equity! equity-file
                       {:venue :testnet
                        :symbol symbol
                        :price price
                        :cash cash
                        :base-qty base-qty
                        :equity equity
                        :peak peak
                        :signal signal}))

(defn- act! [signal price symbol quote cash base-qty fraction
             {:keys [step min-qty min-notional]}]
  (cond
    (and (= :buy signal) (> (* cash fraction) min-notional))
    (let [spend (format "%.2f" (* cash fraction))]
      (log+print! "BUY"
                  (binance/market-order! :testnet symbol "BUY"
                                         {:spend spend})
                  symbol quote))

    (and (= :sell signal) (pos? base-qty))
    (let [q (binance/round-step base-qty step)
          qn (parse-double q)]
      (if (or (< qn min-qty) (< (* qn price) min-notional))
        (println (now) "SELL skipped: below exchange minimums")
        (log+print! "SELL"
                    (binance/market-order! :testnet symbol "SELL"
                                           {:qty q})
                    symbol quote)))

    :else
    (println (now) "HOLD price" price
             quote (format "%.2f" cash)
             "base" (format "%.6f" base-qty))))

(defn- kill! [symbol quote base-qty price equity peak filters]
  (act! :sell price symbol quote 0.0 base-qty 0.0 filters)
  (println (now) "KILL SWITCH: equity" (format "%.2f" equity)
           "breached max drawdown from peak" (format "%.2f" peak)
           "- position flat, bot stopped"))

(defn- pick-params [{:keys [id params adaptive train]} closed opts]
  (if adaptive
    (wf/pick-params id
                    (subvec closed (max 0 (- (count closed) train)))
                    opts)
    params))

(defn- tick!
  "One full cycle. Returns {:peak ... :params ...} or {:killed? true}."
  [{:keys [symbol interval base quote filters fetch-n opts strategy
           fraction max-drawdown]}
   peak last-params]
  (let [closed (vec (butlast (binance/klines-history!
                              symbol interval fetch-n)))
        params (pick-params strategy closed opts)
        signal (last (strat/signals {:id (:id strategy)
                                     :params params}
                                    closed))
        price (:close (peek closed))
        bals (binance/balances! :testnet #{quote base})
        cash (get bals quote 0.0)
        base-qty (get bals base 0.0)
        equity (+ cash (* base-qty price))
        peak (max (or peak equity) equity)]
    (when (not= params last-params)
      (println (now) "params" params))
    (mark-equity! symbol price cash base-qty equity peak signal)
    (save-state! {:peak peak})
    (if (< equity (* peak (- 1.0 max-drawdown)))
      (do (kill! symbol quote base-qty price equity peak filters)
          {:killed? true})
      (do (act! signal price symbol quote cash base-qty fraction filters)
          {:peak peak :params params}))))

(defn start!
  "Poll live candles and trade the testnet account. Blocks until the
  kill switch fires. Signals use closed candles only (the forming
  candle is dropped) to avoid acting on values that can change."
  [{:keys [symbol interval poll-ms strategy risk]
    :or {poll-ms 60000}}]
  (let [{:keys [max-drawdown fraction]
         :or {max-drawdown 0.15 fraction 0.95}} risk
        [base quote] (binance/split-symbol symbol)
        ctx {:symbol symbol
             :interval interval
             :base base
             :quote quote
             :filters (binance/exchange-filters! :testnet symbol)
             :fetch-n (+ (:train strategy 200) 50)
             :opts {:periods-per-year
                    (binance/periods-per-year interval)}
             :strategy strategy
             :fraction fraction
             :max-drawdown max-drawdown}]
    (println "Testnet trading" symbol interval
             "| strategy" (:id strategy)
             "| max-dd" max-drawdown "| size" fraction)
    (loop [peak (:peak (load-state))
           last-params nil]
      (let [r (try
                (tick! ctx peak last-params)
                (catch Exception e
                  (println (now) "ERROR:" (str e))
                  (when-let [d (ex-data e)]
                    (println (now) "DETAIL:" d))
                  {:peak peak :params last-params}))]
        (when-not (:killed? r)
          (Thread/sleep (long poll-ms))
          (recur (:peak r) (:params r)))))))
