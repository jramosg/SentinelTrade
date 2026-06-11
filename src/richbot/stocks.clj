(ns richbot.stocks
  "Stock/ETF advisor loop: checks the market on daily candles and
  sends BUY / SELL recommendations by Telegram; the user executes
  them manually in their broker (Revolut). No broker API. State
  tracks a model portfolio at Yahoo prices so signals, budgets and
  stops stay consistent across restarts — execute the
  recommendations with the suggested amounts to keep your real
  account close to the model."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [richbot.alert :as alert]
            [richbot.dca :as dca]
            [richbot.stocks-data :as sd]
            [richbot.strategy :as strat]
            [richbot.tradelog :as tlog]
            [richbot.walkforward :as wf])
  (:import [java.time Instant LocalDate ZoneOffset]))

(def ^:private state-file ".richbot-stocks-state.edn")
(def ^:private advice-file "advice-stocks.csv")
(def ^:private equity-file "equity-stocks.csv")

(def ^:private min-advice
  "Don't bother the human for less than this (account currency)."
  10.0)


(defn- now [] (str (Instant/now)))
(defn- today [] (str (LocalDate/now ZoneOffset/UTC)))

(defn- load-state []
  (try (edn/read-string (slurp state-file))
       (catch Exception _ nil)))

(defn- save-state! [st] (spit state-file (pr-str st)))

(defn- advise!
  "Print, log and send one actionable recommendation by Telegram."
  [side {:keys [label]} amount currency reason]
  (let [msg (format "ACCION RICHBOT STOCKS: %s ~%.2f %s de %s en Revolut. Motivo: %s"
                    (case side :buy "COMPRA" :sell "VENDE")
                    (double amount) currency label reason)]
    (println (now) msg)
    (alert/send! msg)
    (spit advice-file
          (str (now) "," (name side) ","
               (format "%.2f" (double amount)) "," currency ","
               label "," reason "\n")
          :append true)))

(defn- mark-equity! [slot yahoo price st signal]
  (tlog/append-equity! equity-file
                       {:venue :advisor
                        :symbol (str (name slot) ":" yahoo)
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

(defn- candles!
  "Closed daily candles for a slot (the forming candle dropped)."
  [yahoo n]
  (let [years (max 2 (long (Math/ceil (/ (+ n 60) 252.0))))]
    (vec (butlast (sd/daily-candles! yahoo years)))))

(defn- pick-params [{:keys [id params adaptive train]} closed opts]
  (if adaptive
    (wf/pick-params id
                    (subvec closed (max 0 (- (count closed) train)))
                    opts)
    params))

(defn- recharge!
  "Extra-money dip top-up: when price is at a discount to the
  trend SMA and the cooldown has passed, ask the user for an
  optional extra deposit. Booked as new money: qty up, the monthly
  plan's cash untouched. Evidence (20y SPY): buying >=5/10/15%
  below the SMA200 returned +15/+27/+36% on average a year later."
  [{:keys [tiers cooldown] :or {cooldown 21}}
   ctx closed price-acct discount acct-ccy st]
  (let [amount (when discount
                 (->> tiers
                      (filter #(>= discount (first %)))
                      (map second)
                      (reduce max 0.0)))
        since (if-let [t (:last-recharge st)]
                (count (filter #(> (:open-time %) t) closed))
                cooldown)]
    (if (and amount (pos? amount) (>= since cooldown))
      (do (advise! :buy ctx amount acct-ccy
                   (format "RECARGA opcional con dinero EXTRA (no cuenta el plan mensual): precio %.1f%% bajo la SMA200"
                           (* 100.0 discount)))
          (-> st
              (update :qty + (/ amount price-acct))
              (update :extra (fnil + 0.0) amount)
              (assoc :last-recharge
                     (:open-time (last closed)))))
      st)))

(defn- tick-dca-slot!
  "DCA slot: when a scheduled buy is due, recommend it (sized by
  the trend discount) and book it in the model portfolio. Dips
  additionally trigger optional extra-money recharges. Never
  sells; no stops — the budget is the exposure limit."
  [{slot :name :keys [yahoo currency strategy] :as ctx} acct-ccy st]
  (let [params (merge dca/default-params (:params strategy))
        closed (candles! yahoo (:trend params))
        {:keys [price discount mult base latest spend]}
        (dca/decide params closed st)
        fx (sd/fx-rate! currency acct-ccy)
        price-acct (* price fx)
        equity (+ (:cash st) (* (:qty st) price-acct))
        st (-> st
               (assoc :base base)
               (assoc :equity equity)
               (assoc :peak (max (:peak st) equity)))]
    (mark-equity! slot yahoo price-acct st
                  (when (>= spend min-advice) :buy))
    (let [st (recharge! (:recharge (:params strategy))
                        ctx closed price-acct discount acct-ccy st)]
      (if (< spend min-advice)
        (do (println (now) "ADVISOR DCA HOLD" (name slot)
                     "price" price "discount"
                     (some->> discount (format "%.3f"))
                     "equity" (format "%.2f" equity))
            st)
        ;; Book at the live (possibly gapped) price, not the signal
        ;; close: the user executes now, not at yesterday's close.
        (let [live (or (sd/last-price! yahoo) price)
              live-acct (* live fx)
              reason (str "DCA"
                          (if (> mult 1.0)
                            (format " x%.1f: precio %.1f%% bajo la SMA200"
                                    mult (* 100.0 discount))
                            " mensual")
                          (format " (cierre %.2f, ahora %.2f %s)"
                                  price live currency))]
          (advise! :buy ctx spend acct-ccy reason)
          (-> st
              (update :cash - spend)
              (update :qty + (/ spend live-acct))
              (assoc :last-buy latest)))))))

(defn- sell-model!
  "Book a full exit in the model portfolio and recommend it."
  [{slot :name :as ctx} st price-acct acct-ccy reason]
  (let [amount (* (:qty st) price-acct)]
    (when (>= amount min-advice)
      (advise! :sell ctx amount acct-ccy reason))
    (println (now) "ADVISOR" (name slot) "exit:" reason)
    (-> st (update :cash + amount) (assoc :qty 0.0))))

(defn- tick-signal-slot!
  "Risky slot: trend signals on daily candles, drawdown and daily
  stops. Buys only when flat, sells only when long, so repeated
  ticks of the same candle never repeat a recommendation."
  [{slot :name
    :keys [yahoo currency strategy fraction max-drawdown daily-loss]
    :as ctx}
   acct-ccy st]
  (let [closed (candles! yahoo (:train strategy 504))
        opts {:periods-per-year sd/periods-per-year}
        params (pick-params strategy closed opts)
        signal (last (strat/signals {:id (:id strategy)
                                     :params params}
                                    closed))
        price (:close (peek closed))
        fx (sd/fx-rate! currency acct-ccy)
        price-acct (* price fx)
        equity (+ (:cash st) (* (:qty st) price-acct))
        st (-> st
               (with-day-start equity)
               (assoc :equity equity)
               (assoc :peak (max (:peak st) equity)))]
    (when (not= params (:params st))
      (println (now) "ADVISOR" (name slot) "params" params))
    (mark-equity! slot yahoo price-acct st signal)
    (let [st (assoc st :params params)
          spend (* (:cash st) fraction)
          ;; Live (possibly gapped) price, fetched only when an
          ;; action fires: signals run on yesterday's close, but
          ;; the user executes at today's price.
          live (delay (* (or (sd/last-price! yahoo) price) fx))
          situ (fn []
                 (let [gap (dec (/ @live price-acct))]
                   (format "(cierre %.2f %s, ahora %.2f%s)"
                           price currency (/ @live fx)
                           (if (> (Math/abs gap) 0.03)
                             (format ", OJO gap %+.1f%%"
                                     (* 100.0 gap))
                             ""))))]
      (cond
        (< equity (* (:peak st) (- 1.0 max-drawdown)))
        (-> (sell-model! ctx st @live acct-ccy
                         (str (format "STOP drawdown %.0f%% desde maximo "
                                      (* 100.0 max-drawdown))
                              (situ)))
            (assoc :stopped? true))

        (< equity (* (:day-start-equity st) (- 1.0 daily-loss)))
        (-> (sell-model! ctx st @live acct-ccy
                         (str (format "STOP perdida diaria %.0f%% "
                                      (* 100.0 daily-loss))
                              (situ)))
            (assoc :stopped? true))

        (and (= :buy signal) (>= spend min-advice))
        (do (advise! :buy ctx spend acct-ccy
                     (format "señal %s de entrada %s"
                             (name (:id strategy)) (situ)))
            (-> st
                (update :cash - spend)
                (update :qty + (/ spend @live))))

        (and (= :sell signal)
             (>= (* (:qty st) price-acct) min-advice))
        (sell-model! ctx st @live acct-ccy
                     (format "señal %s de salida %s"
                             (name (:id strategy)) (situ)))

        :else
        (do (println (now) "ADVISOR HOLD" (name slot)
                     "price" price
                     "equity" (format "%.2f" equity))
            st)))))

(defn- tick-slot! [ctx acct-ccy st]
  (cond
    (= :dca (get-in ctx [:strategy :id]))
    (tick-dca-slot! ctx acct-ccy st)

    (:stopped? st) st

    :else
    (tick-signal-slot! ctx acct-ccy st)))

(defn- topup!
  "Once per month, on or after :pay-day, add the monthly
  contribution to each non-stopped slot (split by weight) and
  remind the user by Telegram to fund the broker. The first ever
  tick only records the month: the initial :capital is that
  month's money."
  [st slots contribution pay-day]
  (let [date (LocalDate/now ZoneOffset/UTC)
        m (subs (str date) 0 7)]
    (cond
      (nil? contribution)
      st

      (nil? (:month st))
      (assoc st :month m)

      (or (= m (:month st))
          (< (.getDayOfMonth date) pay-day))
      st

      :else
      (let [active (remove #(get-in st [:slots (:name %) :stopped?])
                           slots)
            total (* contribution (reduce + (map :weight active)))
            msg (str "NUEVO MES richbot stocks: ingresa "
                     (format "%.0f" total)
                     " EUR en Revolut. El modelo reparte: "
                     (str/join
                      ", "
                      (map #(format "%.0f a %s"
                                    (* contribution (:weight %))
                                    (:label %))
                           active)))]
        (println (now) msg)
        (alert/send! msg)
        (-> (reduce (fn [st {slot :name :keys [weight]}]
                      (update-in st [:slots slot :cash]
                                 + (* contribution weight)))
                    st active)
            (assoc :month m))))))

(defn- initial-slot-state [capital {slot :name :keys [weight]}]
  [slot {:cash (double (* capital weight))
         :qty 0.0
         :peak (double (* capital weight))
         :day (today)
         :day-start-equity (double (* capital weight))
         :equity (double (* capital weight))
         :stopped? false}])

(defn plan! [{:keys [stocks]}]
  (let [{:keys [capital currency slots]} stocks]
    (println "Stocks advisor plan | model capital" capital currency)
    (doseq [{slot :name
             :keys [label yahoo weight strategy max-drawdown]} slots]
      (println (format "%s %s (%s) weight %.0f%% capital %.2f %s %s max-dd %s"
                       (name slot) label yahoo (* 100.0 weight)
                       (* capital weight)
                       (name (:id strategy)) (:params strategy)
                       (or max-drawdown "n/a"))))))

(defn start!
  "Run the advisor loop. Sends recommendations only - no broker
  orders - so it needs no confirmation, but it is pointless
  without Telegram configured."
  [{:keys [stocks]}]
  (let [{:keys [capital contribution currency poll-ms slots
                pay-day max-errors]
         :or {currency "EUR" poll-ms 600000 pay-day 7
              max-errors 5}} stocks]
    (assert (some-> capital pos?) "config :stocks :capital missing")
    (assert (seq slots) "config :stocks :slots missing")
    (assert (<= (reduce + (map :weight slots)) 1.0)
            "stocks weights exceed 100%")
    (when-not (alert/enabled?)
      (println "WARNING: Telegram no configurado"
               "(TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID);"
               "las recomendaciones solo saldran por consola"))
    (let [slots (mapv #(merge {:max-drawdown 0.45
                               :daily-loss 0.20
                               :fraction 0.95}
                              %)
                      slots)
          initial (into {} (map #(initial-slot-state capital %)
                                slots))
          st {:slots (merge-with merge initial
                                 (:slots (load-state)))}]
      (println "STOCKS ADVISOR | model capital" capital currency
               "| contribution" contribution "/month")
      (plan! {:stocks (assoc stocks :slots slots)})
      (alert/send! (str "STOCKS ADVISOR iniciado, capital modelo "
                        capital " " currency
                        (when contribution
                          (str " + " contribution " " currency
                               " al mes"))
                        " - las ordenes las ejecutas TU en Revolut"))
      (loop [st st errors 0]
        (let [r (try
                  {:st (reduce
                        (fn [st ctx]
                          (update-in st [:slots (:name ctx)]
                                     #(tick-slot! ctx currency %)))
                        (topup! st slots contribution pay-day)
                        slots)}
                  (catch Exception e
                    (let [msg (str (now) " ADVISOR ERROR: " e)]
                      (println msg)
                      (alert/send! msg))
                    (when-let [d (ex-data e)]
                      (println (now) "DETAIL:" d))
                    {:st st :error? true}))]
          (save-state! (:st r))
          (if (and (:error? r) (>= (inc errors) max-errors))
            (let [msg (str (now) " ADVISOR STOPPED: " (inc errors)
                           " consecutive errors")]
              (println msg)
              (alert/send! msg))
            (do (Thread/sleep (long poll-ms))
                (recur (:st r)
                       (if (:error? r) (inc errors) 0)))))))))
