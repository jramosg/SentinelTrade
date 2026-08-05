(ns richbot.swing-alerts
  "Intraday stock setup monitor with durable Telegram alert state.
  It never connects to a broker or executes an order."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [richbot.alert :as alert]
            [richbot.stocks-data :as sd])
  (:import [java.time Duration Instant LocalDate LocalTime ZoneId]))

(def ^:private default-state-file ".richbot-swing-alerts-state.edn")
(def ^:private default-log-file "swing-alerts-events.log")
(def ^:private default-zone "America/New_York")
(def ^:private default-interval-minutes 5)
(def ^:private default-max-lag-minutes 30)
(def ^:private yahoo-intervals #{1 2 5 15 30 60 90})
(def ^:private symbol-pattern #"[A-Za-z0-9.^=_-]{1,32}")
(def ^:private range-pattern #"(?:[1-9][0-9]*(?:d|mo|y)|ytd|max)")

(defn- parse-date [value]
  (some-> value LocalDate/parse))

(defn- parse-time [value]
  (some-> value LocalTime/parse))

(defn- market-date [^Instant instant ^ZoneId zone]
  (-> instant (.atZone zone) .toLocalDate))

(defn- market-time [^Instant instant ^ZoneId zone]
  (-> instant (.atZone zone) .toLocalTime))

(defn- candle-date [candle ^ZoneId zone]
  (-> (Instant/ofEpochMilli (:open-time candle))
      (.atZone zone)
      .toLocalDate))

(defn- terminal? [state]
  (contains? #{:closed :expired :skipped} (:status state)))

(defn- require-setup [setup valid? message]
  (when-not valid?
    (throw (ex-info (str "Invalid swing setup: " message)
                    {:id (:id setup) :message message}))))

(defn- increasing? [values]
  (every? (fn [[left right]] (< left right))
          (partition 2 1 values)))

(defn- validate-setup [setup]
  (let [{:keys [id symbol capital interval-minutes range-str
                max-lag-minutes entry-start entry-end not-before
                not-after session-open-min session-open-max stop
                targets expires exit-time]
         :or {interval-minutes default-interval-minutes
              range-str "5d"
              max-lag-minutes default-max-lag-minutes
              exit-time "15:55"}} setup
        entry (:entry setup)
        entry-values (mapv entry [:min :trigger :max])]
    (require-setup setup (keyword? id) ":id must be a keyword")
    (require-setup setup
                   (and (string? symbol)
                        (re-matches symbol-pattern symbol))
                   ":symbol contains unsupported characters")
    (require-setup setup (some-> capital pos?)
                   ":capital must be positive")
    (require-setup setup (contains? yahoo-intervals interval-minutes)
                   ":interval-minutes is not supported by Yahoo")
    (require-setup setup (re-matches range-pattern range-str)
                   ":range-str is invalid")
    (require-setup setup (some-> max-lag-minutes pos?)
                   ":max-lag-minutes must be positive")
    (require-setup setup (every? number? entry-values)
                   ":entry min, trigger, and max must be numbers")
    (require-setup setup
                   (apply <= (map double entry-values))
                   ":entry must satisfy min <= trigger <= max")
    (require-setup setup (and (number? stop)
                              (< (double stop)
                                 (double (:min entry))))
                   ":stop must be below the entry range")
    (require-setup setup (and (seq targets)
                              (every? #(and (number? (:price %))
                                            (number? (:fraction %))
                                            (pos? (:fraction %)))
                                      targets))
                   ":targets need positive prices and fractions")
    (let [prices (mapv (comp double :price) targets)
          fractions (mapv (comp double :fraction) targets)]
      (require-setup setup (and (increasing? prices)
                                (> (first prices)
                                   (double (:max entry))))
                     ":target prices must increase above entry")
      (require-setup setup (<= (reduce + fractions) 1.0)
                     ":target fractions exceed 100%"))
    (let [start (parse-date entry-start)
          end (parse-date entry-end)
          expiry (parse-date expires)]
      (require-setup setup
                     (and start end expiry
                          (not (.isAfter start end))
                          (not (.isBefore expiry end)))
                     "dates must satisfy entry-start <= entry-end <= expires"))
    (let [start (parse-time not-before)
          end (parse-time not-after)]
      (require-setup setup
                     (and start end (.isBefore start end))
                     ":not-before must be earlier than :not-after"))
    (parse-time exit-time)
    (require-setup setup
                   (or (nil? session-open-min)
                       (nil? session-open-max)
                       (<= (double session-open-min)
                           (double session-open-max)))
                   "session open bounds are reversed")
    setup))

(defn- initial-state [state]
  (merge {:status :armed
          :targets-hit #{}
          :pending-events []}
         state))

(defn- within? [value lower upper]
  (and (or (nil? lower) (not (.isBefore value lower)))
       (or (nil? upper) (not (.isAfter value upper)))))

(defn- fresh-candle?
  [candle ^Instant instant max-lag-minutes]
  (when candle
    (let [open (Instant/ofEpochMilli (:open-time candle))
          age (.toMinutes (Duration/between open instant))]
      (and (not (neg? age))
           (<= age max-lag-minutes)))))

(defn- closed-candle?
  [candle ^Instant instant interval-minutes]
  (let [close-time (-> (Instant/ofEpochMilli (:open-time candle))
                       (.plusSeconds (* 60 interval-minutes)))]
    (not (.isAfter close-time instant))))

(defn- entry-date-valid? [today entry-start entry-end]
  (within? today entry-start entry-end))

(defn- entry-confirmed?
  [closed-bars trigger confirm-bars]
  (let [confirmation (take-last confirm-bars closed-bars)]
    (and (= confirm-bars (count confirmation))
         (every? #(>= (double (:close %)) trigger)
                 confirmation))))

(defn- target-events [setup state price]
  (let [hit (:targets-hit state)
        targets (remove #(contains? hit (:price %))
                        (:targets setup))]
    (->> targets
         (filter #(>= price (double (:price %))))
         (mapv #(assoc % :type :target :market-price price)))))

(defn- apply-target-events [setup state events]
  (let [new-hits (into (:targets-hit state) (map :price events))
        all-hit? (= (set (map :price (:targets setup))) new-hits)]
    (cond-> (assoc state :targets-hit new-hits)
      all-hit? (assoc :status :closed))))

(defn evaluate-setup
  "Pure setup evaluation. Returns {:state .. :events [...]}. The latest
  forming candle can trigger stops and targets; entries require completed
  confirmation candles."
  [setup persisted-state candles ^Instant instant]
  (let [state (initial-state persisted-state)
        zone (ZoneId/of (:zone setup default-zone))
        today (market-date instant zone)
        time-now (market-time instant zone)
        entry-start (parse-date (:entry-start setup))
        entry-end (parse-date (:entry-end setup))
        expires (parse-date (:expires setup))
        exit-time (parse-time (:exit-time setup "15:55"))
        interval-minutes (:interval-minutes
                          setup default-interval-minutes)
        max-lag-minutes (:max-lag-minutes
                         setup default-max-lag-minutes)
        session-bars (filterv #(= today (candle-date % zone)) candles)
        closed-bars (filterv #(closed-candle? % instant interval-minutes)
                             session-bars)
        latest (peek session-bars)
        price (some-> latest :close double)
        session-open (some-> session-bars first :open double)
        fresh? (fresh-candle? latest instant max-lag-minutes)
        status (:status state)]
    (cond
      (terminal? state)
      {:state state :events []}

      (and (= :armed status)
           entry-end
           (.isAfter today entry-end))
      {:state (assoc state :status :expired)
       :events [{:type :entry-expired :market-price price}]}

      (and (= :watching status)
           expires
           (or (.isAfter today expires)
               (and (= today expires)
                    (not (.isBefore time-now exit-time)))))
      {:state (assoc state :status :closed)
       :events [{:type :time-exit :market-price price}]}

      (and (= :watching status)
           fresh?
           price
           (some-> (:stop setup) double (>= price)))
      {:state (assoc state :status :closed)
       :events [{:type :stop :market-price price}]}

      (and (= :watching status) fresh? price)
      (let [events (target-events setup state price)]
        {:state (if (seq events)
                  (apply-target-events setup state events)
                  state)
         :events events})

      (and (= :armed status)
           fresh?
           session-open
           (or (some-> (:session-open-min setup)
                       double
                       (> session-open))
               (some-> (:session-open-max setup)
                       double
                       (< session-open))))
      {:state (assoc state :status :skipped)
       :events [{:type :skipped-open
                 :market-price price
                 :session-open session-open}]}

      (and (= :armed status)
           fresh?
           price
           (entry-date-valid? today entry-start entry-end)
           (within? time-now
                    (parse-time (:not-before setup))
                    (parse-time (:not-after setup)))
           (>= price (double (get-in setup [:entry :min])))
           (<= price (double (get-in setup [:entry :max])))
           (entry-confirmed? closed-bars
                             (double (get-in setup [:entry :trigger]))
                             (get-in setup [:entry :confirm-bars] 3)))
      {:state (assoc state
                     :status :watching
                     :entry-price price
                     :entry-alerted-at (str instant))
       :events [{:type :entry :market-price price}]}

      :else
      {:state state :events []})))

(defn- money [value]
  (format "%.2f" (double value)))

(defn event-message
  "Human-readable Telegram text for one setup event."
  [setup event]
  (let [symbol (:symbol setup)
        price (:market-price event)
        price-text (if price (money price) "sin precio fresco")]
    (case (:type event)
      :entry
      (format (str "SWING %s - ENTRADA CONFIRMADA a %s USD. "
                   "Compra manual ~%.0f %s en %s, maximo %.2f USD. "
                   "Stop %.2f; objetivos %s. No es una orden automatica.")
              symbol price-text (double (:capital setup))
              (:account-currency setup "EUR") (:venue setup "Revolut")
              (double (get-in setup [:entry :max]))
              (double (:stop setup))
              (str/join ", " (map #(money (:price %))
                                  (:targets setup))))

      :stop
      (format (str "SWING %s - STOP activado a %s USD. "
                   "Vende manualmente toda la posicion restante.")
              symbol price-text)

      :target
      (format (str "SWING %s - OBJETIVO %.2f alcanzado; precio %s USD. "
                   "Vende manualmente %.0f%% de la posicion inicial.")
              symbol (double (:price event)) price-text
              (* 100.0 (double (:fraction event))))

      :time-exit
      (format (str "SWING %s - VENCIMIENTO del plan; precio %s USD. "
                   "Cierra manualmente cualquier posicion restante.")
              symbol price-text)

      :skipped-open
      (format (str "SWING %s - SIN OPERACION. Apertura %.2f USD fuera "
                   "del rango permitido; ultimo precio %s USD.")
              symbol (double (:session-open event)) price-text)

      :entry-expired
      (format (str "SWING %s - entrada caducada sin confirmacion. "
                   "No persigas el precio; ultimo dato %s USD.")
              symbol price-text))))

(defn- load-state! [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    {:setups {}}))

(defn- save-state! [path state]
  (spit path (pr-str state)))

(defn- log-event! [path setup event message]
  (spit path
        (str (pr-str {:at (str (Instant/now))
                      :setup (:id setup)
                      :event event
                      :message message})
             "\n")
        :append true))

(defn- send-event! [log-file setup event]
  (let [message (event-message setup event)]
    (println (str (Instant/now)) message)
    (if (or (not (alert/enabled?))
            (alert/send! message))
      (do
        (log-event! log-file setup event message)
        true)
      false)))

(defn- drain-pending!
  [root-state state-file log-file setup]
  (let [id (:id setup)]
    (loop [state root-state]
      (if-let [event (first (get-in state
                                    [:setups id :pending-events]))]
        (if (send-event! log-file setup event)
          (let [state (update-in state [:setups id :pending-events]
                                 #(vec (rest %)))]
            (save-state! state-file state)
            (recur state))
          state)
        state))))

(defn- active-setups [config]
  (->> (:setups config)
       (filter #(not= false (:enabled? %)))
       (mapv validate-setup)))

(defn- tick-setup!
  [root-state setup instant state-file log-file]
  (let [id (:id setup)
        root-state (drain-pending! root-state state-file log-file setup)
        old-state (initial-state (get-in root-state [:setups id]))]
    (cond
      (seq (:pending-events old-state))
      root-state

      (terminal? old-state)
      root-state

      :else
      (let [candles (sd/intraday-candles!
                     (:symbol setup)
                     {:interval (str (:interval-minutes
                                     setup default-interval-minutes)
                                     "m")
                      :range-str (:range-str setup "5d")
                      :include-prepost? false})
            {:keys [state events]}
            (evaluate-setup setup old-state candles instant)
            state (update state :pending-events into events)
            root-state (assoc-in root-state [:setups id] state)]
        (save-state! state-file root-state)
        (drain-pending! root-state state-file log-file setup)))))

(defn tick!
  "Evaluate every enabled setup once, send new events, and persist state."
  [{:keys [state-file log-file] :as config}]
  (let [state-file (or state-file default-state-file)
        log-file (or log-file default-log-file)
        instant (Instant/now)
        state (reduce #(tick-setup! %1 %2 instant state-file log-file)
                      (load-state! state-file)
                      (active-setups config))]
    (save-state! state-file state)
    state))

(defn plan! [{:keys [swing-alerts]}]
  (println "Swing alert plan - manual execution only")
  (doseq [setup (active-setups swing-alerts)]
    (println (format (str "%s %s | entry %.2f-%.2f after %d bars >= %.2f "
                          "| stop %.2f | targets %s | expires %s")
                     (name (:id setup)) (:symbol setup)
                     (double (get-in setup [:entry :min]))
                     (double (get-in setup [:entry :max]))
                     (get-in setup [:entry :confirm-bars] 3)
                     (double (get-in setup [:entry :trigger]))
                     (double (:stop setup))
                     (str/join ", " (map #(money (:price %))
                                         (:targets setup)))
                     (:expires setup)))))

(defn run-once! [{:keys [swing-alerts]}]
  (assert (seq (active-setups swing-alerts))
          "config :swing-alerts :setups missing")
  (tick! swing-alerts))

(defn status! [{:keys [swing-alerts]}]
  (let [path (:state-file swing-alerts default-state-file)
        state (load-state! path)]
    (plan! {:swing-alerts swing-alerts})
    (println "State:" (pr-str state))
    state))

(defn start!
  "Run the intraday alert loop. It sends advice only and never trades."
  [{:keys [swing-alerts]}]
  (let [{:keys [poll-ms max-errors]
         :or {poll-ms 60000 max-errors 5}} swing-alerts
        setups (active-setups swing-alerts)]
    (assert (seq setups) "config :swing-alerts :setups missing")
    (when-not (alert/enabled?)
      (println "WARNING: Telegram no configurado; alertas solo en consola"))
    (plan! {:swing-alerts swing-alerts})
    (alert/send! (format (str "SWING ALERTS iniciado: %d setup(s), "
                              "ejecucion siempre manual")
                         (count setups)))
    (loop [errors 0]
      (let [failed? (try
                      (tick! swing-alerts)
                      false
                      (catch Exception e
                        (let [message (str "SWING ALERTS ERROR: " e)]
                          (println (str (Instant/now)) message)
                          (alert/send! message))
                        (when-let [data (ex-data e)]
                          (println "DETAIL:" data))
                        true))
            errors (if failed? (inc errors) 0)]
        (if (>= errors max-errors)
          (let [message (format (str "SWING ALERTS detenido tras %d "
                                     "errores consecutivos")
                                errors)]
            (println message)
            (alert/send! message))
          (do
            (Thread/sleep (long poll-ms))
            (recur errors)))))))
