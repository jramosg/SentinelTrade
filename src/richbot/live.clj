(ns richbot.live
  "Live spot trading on binance.com with REAL money.
  Safety rails: explicit startup confirmation, hard capital cap
  tracked locally (the rest of the account is never touched),
  exchange filters (LOT_SIZE / NOTIONAL), a conservative 10%
  max-drawdown kill switch and a CSV log of every order.
  Network or API errors are logged and retried, never fatal."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [richbot.alert :as alert]
            [richbot.binance :as binance]
            [richbot.dca :as dca]
            [richbot.strategy :as strat]
            [richbot.tradelog :as tlog]
            [richbot.walkforward :as wf])
  (:import [java.time Instant LocalDate ZoneOffset]))

(def ^:private state-file ".richbot-live-state.edn")
(def ^:private portfolio-state-file ".richbot-portfolio-state.edn")
(def ^:private csv-file "trades-live.csv")
(def ^:private equity-file "equity-live.csv")
(def ^:private portfolio-equity-file "equity-portfolio.csv")

(defn- now [] (str (Instant/now)))

(defn- today []
  (str (LocalDate/now ZoneOffset/UTC)))

(defn- load-state []
  (try (edn/read-string (slurp state-file))
       (catch Exception _ nil)))

(defn- save-state! [st] (spit state-file (pr-str st)))

(defn- load-portfolio-state []
  (try (edn/read-string (slurp portfolio-state-file))
       (catch Exception _ nil)))

(defn- save-portfolio-state! [st]
  (spit portfolio-state-file (pr-str st)))

(defn- confirm! []
  (println "*** MODO LIVE: ordenes REALES con dinero REAL"
           "en binance.com ***")
  (print "Escribe SI (en mayusculas) para continuar: ")
  (flush)
  (= "SI" (str/trim (or (read-line) ""))))

(defn- pick-params [{:keys [id params adaptive train]} closed opts]
  (if adaptive
    (wf/pick-params id
                    (subvec closed (max 0 (- (count closed) train)))
                    opts)
    params))

(defn- log+print! [side resp symbol quote]
  (let [msg (str (now) " " side " " (:executedQty resp) " " symbol
                 " for " (:cummulativeQuoteQty resp) " " quote
                 " | order " (:orderId resp) " " (:status resp))]
    (println msg)
    (alert/send! msg))
  (tlog/append! csv-file
                {:venue :live :symbol symbol :side side :resp resp}))

(defn- mark-equity! [symbol price st signal]
  (tlog/append-equity! equity-file
                       {:venue :live
                        :symbol symbol
                        :price price
                        :cash (:cash st)
                        :base-qty (:qty st)
                        :equity (:equity st)
                        :peak (:peak st)
                        :signal signal}))

(defn- mark-portfolio-equity! [slot symbol price st signal]
  (tlog/append-equity! portfolio-equity-file
                       {:venue :live
                        :symbol (str (name slot) ":" symbol)
                        :price price
                        :cash (:cash st)
                        :base-qty (:qty st)
                        :equity (:equity st)
                        :peak (:peak st)
                        :signal signal}))

(defn- with-day-start [st equity]
  (let [day (today)]
    (if (= day (:day st))
      st
      (assoc st :day day :day-start-equity equity))))

(defn- buy!
  "Spend the tracked cash (clamped by the real quote balance, so a
  stale state file can never over-spend) on the base asset."
  [{:keys [cash] :as st} symbol quote fraction {:keys [min-notional]}]
  (let [avail (get (binance/balances! :live #{quote}) quote 0.0)
        spend (* (min cash avail) fraction)]
    (if (<= spend min-notional)
      st
      (let [resp (binance/market-order!
                  :live symbol "BUY"
                  {:spend (format "%.2f" spend)})
            got (parse-double (:executedQty resp "0"))
            paid (parse-double (:cummulativeQuoteQty resp "0"))]
        (log+print! "BUY" resp symbol quote)
        (-> st (update :cash - paid) (update :qty + got))))))

(defn- dca-buy!
  "Spend exactly amount (clamped by the tracked cash and the real
  quote balance) on the base asset. Returns st unchanged when the
  spend would fall below the exchange minimum notional."
  [{:keys [cash] :as st} symbol quote amount {:keys [min-notional]}]
  (let [avail (get (binance/balances! :live #{quote}) quote 0.0)
        spend (min amount cash avail)]
    (if (<= spend min-notional)
      st
      (let [resp (binance/market-order!
                  :live symbol "BUY" {:spend (format "%.2f" spend)})
            got (parse-double (:executedQty resp "0"))
            paid (parse-double (:cummulativeQuoteQty resp "0"))]
        (log+print! "BUY" resp symbol quote)
        (-> st (update :cash - paid) (update :qty + got))))))

(defn- sell!
  "Sell the tracked position (capped by the real balance, floored
  to the exchange step size)."
  [{:keys [qty] :as st} symbol base quote price
   {:keys [step min-qty min-notional]}]
  (let [avail (get (binance/balances! :live #{base}) base 0.0)
        q (binance/round-step (min qty avail) step)
        qn (parse-double q)]
    (if (or (< qn min-qty) (< (* qn price) min-notional))
      st
      (let [resp (binance/market-order! :live symbol "SELL" {:qty q})
            sold (parse-double (:executedQty resp "0"))
            got (parse-double (:cummulativeQuoteQty resp "0"))]
        (log+print! "SELL" resp symbol quote)
        (-> st (update :cash + got) (update :qty - sold))))))

(defn- tick!
  "One full cycle: candles, params, signal, kill-switch check, act.
  Returns {:st ... :params ...} or {:killed? true}."
  [{:keys [symbol interval base quote filters fetch-n opts strategy
           fraction max-drawdown daily-loss]}
   st last-params]
  (let [closed (vec (butlast (binance/klines-history!
                              symbol interval fetch-n)))
        params (pick-params strategy closed opts)
        signal (last (strat/signals {:id (:id strategy)
                                     :params params}
                                    closed))
        price (:close (peek closed))
        equity (+ (:cash st) (* (:qty st) price))
        st (-> st
               (with-day-start equity)
               (assoc :equity equity)
               (assoc :peak (max (:peak st) equity)))]
    (when (not= params last-params)
      (println (now) "params" params))
    (mark-equity! symbol price st signal)
    (save-state! st)
    (cond
      (< equity (* (:peak st) (- 1.0 max-drawdown)))
      (let [st (sell! st symbol base quote price filters)]
        (save-state! st)
        (let [msg (str (now) " KILL SWITCH: equity "
                       (format "%.2f" equity)
                       " breached " max-drawdown
                       " drawdown from peak "
                       (format "%.2f" (:peak st)) " - stopped")]
          (println msg)
          (alert/send! msg))
        {:killed? true})

      (< equity (* (:day-start-equity st) (- 1.0 daily-loss)))
      (let [st (sell! st symbol base quote price filters)]
        (save-state! st)
        (let [msg (str (now) " DAILY LOSS STOP: equity "
                       (format "%.2f" equity)
                       " breached " daily-loss
                       " from day start "
                       (format "%.2f" (:day-start-equity st))
                       " - stopped")]
          (println msg)
          (alert/send! msg))
        {:killed? true})

      :else
      (let [st (case signal
                 :buy (buy! st symbol quote fraction filters)
                 :sell (sell! st symbol base quote price filters)
                 (do (println (now) "HOLD price" price
                              "equity" (format "%.2f" equity))
                     st))]
        (save-state! st)
        {:st st :params params}))))

(defn- slot-ctx
  [{:keys [name symbol strategy weight max-drawdown daily-loss fraction]}
   interval]
  (let [[base quote] (binance/split-symbol symbol)]
    {:name name
     :symbol symbol
     :interval interval
     :base base
     :quote quote
     :filters (binance/exchange-filters! :live symbol)
     :fetch-n (+ (:train strategy 200) 50)
     :opts {:periods-per-year (binance/periods-per-year interval)}
     :strategy strategy
     :weight weight
     :fraction (or fraction 1.0)
     :max-drawdown max-drawdown
     :daily-loss daily-loss}))

(defn- initial-slot-state [capital {:keys [name weight]}]
  [name {:cash (double (* capital weight))
         :qty 0.0
         :peak (double (* capital weight))
         :day (today)
         :day-start-equity (double (* capital weight))
         :equity (double (* capital weight))
         :stopped? false}])

(defn- merge-slot-states [initial loaded]
  (merge-with merge initial (:slots loaded)))

(defn- stop-slot! [slot symbol base quote price filters st reason]
  (let [st (sell! st symbol base quote price filters)
        msg (str (now) " PORTFOLIO " (name slot) " STOP: " reason
                 " equity " (format "%.2f" (:equity st)))]
    (println msg)
    (alert/send! msg)
    (assoc st :stopped? true)))

(defn- tick-dca-slot!
  "One DCA cycle: scheduled accumulation buys sized by the discount
  to the long trend SMA. Never sells, so the drawdown and daily-loss
  stops do not apply — the budget is the only exposure limit."
  [{slot :name :keys [symbol interval quote filters strategy]} st]
  (let [params (merge dca/default-params (:params strategy))
        closed (vec (butlast (binance/klines-history!
                              symbol interval
                              (+ (:trend params) 3))))
        {:keys [price discount mult base latest spend]}
        (dca/decide params closed st)
        equity (+ (:cash st) (* (:qty st) price))
        st (-> st
               (assoc :base base)
               (assoc :equity equity)
               (assoc :peak (max (:peak st) equity)))]
    (mark-portfolio-equity! slot symbol price st
                            (when (pos? spend) :buy))
    (if (pos? spend)
      (let [_ (println (now) "DCA BUY" (name slot) symbol
                       "spend" (format "%.2f" spend)
                       "mult" mult "discount"
                       (some->> discount (format "%.3f")))
            st2 (dca-buy! st symbol quote spend filters)]
        (when (= (:cash st2) (:cash st))
          (println (now) "DCA SKIP" (name slot) symbol
                   "- below min notional or no funds"))
        {:st (cond-> st2
               (< (:cash st2) (:cash st)) (assoc :last-buy latest))})
      (do (println (now) "DCA HOLD" (name slot) symbol
                   "price" price "discount"
                   (some->> discount (format "%.3f"))
                   "equity" (format "%.2f" equity))
          {:st st}))))

(defn- tick-signal-slot!
  [{slot :name :keys [symbol interval base quote filters fetch-n opts
                      strategy fraction max-drawdown daily-loss]}
   st last-params]
  (let [closed (vec (butlast (binance/klines-history!
                              symbol interval fetch-n)))
        params (pick-params strategy closed opts)
        signal (last (strat/signals {:id (:id strategy)
                                     :params params}
                                    closed))
        price (:close (peek closed))
        equity (+ (:cash st) (* (:qty st) price))
        st (-> st
               (with-day-start equity)
               (assoc :equity equity)
               (assoc :peak (max (:peak st) equity)))]
    (when (not= params last-params)
      (println (now) "portfolio" (name slot) symbol "params" params))
    (mark-portfolio-equity! slot symbol price st signal)
    (cond
      (< equity (* (:peak st) (- 1.0 max-drawdown)))
      {:st (stop-slot! slot symbol base quote price filters st
                       (str "drawdown " max-drawdown))
       :params params}

      (< equity (* (:day-start-equity st) (- 1.0 daily-loss)))
      {:st (stop-slot! slot symbol base quote price filters st
                       (str "daily loss " daily-loss))
       :params params}

      :else
      (let [st (case signal
                 :buy (buy! st symbol quote fraction filters)
                 :sell (sell! st symbol base quote price filters)
                 (do (println (now) "PORTFOLIO HOLD" (name slot)
                              symbol "price" price "equity"
                              (format "%.2f" equity))
                     st))]
        {:st st :params params}))))

(defn- tick-slot! [ctx st last-params]
  (cond
    (= :dca (get-in ctx [:strategy :id]))
    (assoc (tick-dca-slot! ctx st) :params last-params)

    (:stopped? st)
    {:st st :params last-params}

    :else
    (tick-signal-slot! ctx st last-params)))

(defn start!
  "Run the live loop. Trades at most the configured :capital; the
  rest of the account is never touched. Pass assume-yes? true for
  headless starts (VPS/systemd) — the explicit confirmation is then
  the literal `yes` argument on the command line."
  ([config] (start! config false))
  ([{:keys [symbol interval poll-ms strategy live]
     :or {poll-ms 60000}}
    assume-yes?]
   (let [{:keys [capital max-drawdown daily-loss fraction max-errors]
          :or {max-drawdown 0.10
               daily-loss 0.05
               fraction 0.95
               max-errors 5}} live]
     (assert (some-> capital pos?) "config :live :capital missing")
     (when (or assume-yes? (confirm!))
       (let [[base quote] (binance/split-symbol symbol)
             ctx {:symbol symbol
                  :interval interval
                  :base base
                  :quote quote
                  :filters (binance/exchange-filters! :live symbol)
                  :fetch-n (+ (:train strategy 200) 50)
                  :opts {:periods-per-year
                         (binance/periods-per-year interval)}
                  :strategy strategy
                  :fraction fraction
                  :max-drawdown max-drawdown
                  :daily-loss daily-loss}
             initial {:cash (double capital) :qty 0.0
                      :peak (double capital)
                      :day (today)
                      :day-start-equity (double capital)
                      :equity (double capital)}]
         (println "LIVE" symbol interval "| capital" capital
                  "| max-dd" max-drawdown "| daily-loss" daily-loss
                  "| size" fraction)
         (println "filters" (:filters ctx))
         (alert/send! (str "LIVE started " symbol " " interval
                           " capital " capital " " quote))
         (loop [st (merge initial (or (load-state) {}))
                last-params nil
                errors 0]
           (let [r (try
                     (tick! ctx st last-params)
                     (catch Exception e
                       (let [msg (str (now) " ERROR: " e)]
                         (println msg)
                         (alert/send! msg))
                       (when-let [d (ex-data e)]
                         (println (now) "DETAIL:" d))
                       {:st st :params last-params
                        :error? true}))]
             (if (and (:error? r) (>= (inc errors) max-errors))
               (let [msg (str (now) " STOPPED: " (inc errors)
                              " consecutive errors")]
                 (println msg)
                 (alert/send! msg))
               (when-not (:killed? r)
                 (Thread/sleep (long poll-ms))
                 (recur (:st r)
                        (:params r)
                        (if (:error? r) (inc errors) 0)))))))))))

(defn- assert-portfolio! [{:keys [capital slots]}]
  (assert (some-> capital pos?) "config :portfolio :capital missing")
  (assert (seq slots) "config :portfolio :slots missing")
  (let [weight (reduce + (map :weight slots))]
    (assert (<= weight 1.0)
            (str "portfolio weights exceed 100%: " weight))))

(defn- tick-portfolio! [ctxs st params]
  (reduce (fn [{:keys [st params]} ctx]
            (let [slot (:name ctx)
                  slot-st (get-in st [:slots slot])
                  r (tick-slot! ctx slot-st (get params slot))]
              {:st (assoc-in st [:slots slot] (:st r))
               :params (assoc params slot (:params r))}))
          {:st st :params params}
          ctxs))

(defn start-portfolio!
  "Run multi-asset live portfolio mode. Each slot has its own capital,
  strategy, drawdown stop and state under .richbot-portfolio-state.edn."
  ([config] (start-portfolio! config false))
  ([{:keys [interval poll-ms portfolio]
     :or {interval "4h" poll-ms 60000}}
    assume-yes?]
   (assert-portfolio! portfolio)
   (when (or assume-yes? (confirm!))
     (let [{:keys [capital slots max-errors]
            :or {max-errors 5}} portfolio
           slots (mapv #(merge {:daily-loss 0.25
                                :max-drawdown 0.50}
                               %)
                       slots)
           ctxs (mapv #(slot-ctx % interval) slots)
           initial-slots (into {} (map #(initial-slot-state capital %)
                                       slots))
           initial {:slots initial-slots}
           loaded (load-portfolio-state)
           st (assoc initial :slots (merge-slot-states initial-slots
                                                       loaded))]
       (println "PORTFOLIO LIVE" interval "| capital" capital)
       (doseq [{slot :name
                :keys [symbol weight strategy max-drawdown
                       daily-loss]} slots]
         (println " slot" (name slot) symbol
                  "| weight" weight
                  "| capital" (format "%.2f" (* capital weight))
                  "| strategy" (:id strategy)
                  "| max-dd" max-drawdown
                  "| daily-loss" daily-loss))
       (alert/send! (str "PORTFOLIO LIVE started capital " capital))
       (loop [st st
              params {}
              errors 0]
         (let [r (try
                   (tick-portfolio! ctxs st params)
                   (catch Exception e
                     (let [msg (str (now) " PORTFOLIO ERROR: " e)]
                       (println msg)
                       (alert/send! msg))
                     (when-let [d (ex-data e)]
                       (println (now) "DETAIL:" d))
                     {:st st :params params :error? true}))]
           (save-portfolio-state! (:st r))
           (if (and (:error? r) (>= (inc errors) max-errors))
             (let [msg (str (now) " PORTFOLIO STOPPED: " (inc errors)
                            " consecutive errors")]
               (println msg)
               (alert/send! msg))
             (do
               (Thread/sleep (long poll-ms))
               (recur (:st r)
                      (:params r)
                      (if (:error? r) (inc errors) 0))))))))))
