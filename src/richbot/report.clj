(ns richbot.report
  "Weekly Telegram digest: equity, positions and drawdown for the
  crypto portfolio and the stocks model, plus open dip trades. Read
  only — it books nothing and changes no state. Run from cron."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [richbot.alert :as alert]
            [richbot.binance :as binance]
            [richbot.stocks-data :as sd]))

(defn- load-edn [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

(defn- crypto-price! [symbol]
  (:close (peek (binance/klines! symbol "1m" 1))))

(defn- stock-price-eur! [{:keys [yahoo currency]}]
  (* (sd/last-price! yahoo)
     (sd/fx-rate! currency "EUR")))

(defn- slot-line
  "One line per slot: equity, position value, cash, drawdown."
  [slot-name {:keys [cash qty peak stopped?]} price ccy]
  (let [pos (* (or qty 0.0) price)
        equity (+ (or cash 0.0) pos)
        dd (if (pos? peak) (- 1.0 (/ equity peak)) 0.0)]
    {:equity equity
     :text (format "%s: %.2f %s (pos %.2f, cash %.2f, dd %.1f%%)%s"
                   (name slot-name) equity ccy pos cash
                   (* 100.0 (max 0.0 dd))
                   (if stopped? " STOPPED" ""))}))

(defn- section
  "Sum and per-slot lines for one state file. price-fn resolves the
  current price of a slot from its config."
  [title state-file slots price-fn ccy]
  (let [st (load-edn state-file)
        by-name (into {} (map (juxt :name identity) slots))
        rows (keep (fn [[slot-name slot-st]]
                     (when-let [cfg (by-name slot-name)]
                       (try
                         (slot-line slot-name slot-st
                                    (price-fn cfg) ccy)
                         (catch Exception e
                           {:equity 0.0
                            :text (str (name slot-name) ": ERROR "
                                       (ex-message e))}))))
                   (:slots st))]
    (when (seq rows)
      (str title " | total "
           (format "%.2f %s" (reduce + (map :equity rows)) ccy)
           "\n"
           (str/join "\n" (map :text rows))))))

(defn- dips-section []
  (let [st (load-edn ".richbot-dips-state.edn")
        lines (keep (fn [[symbol {:keys [opened trade]}]]
                      (when trade
                        (let [now (try (sd/last-price! symbol)
                                       (catch Exception _ nil))
                              pnl (when (and now (:entry trade))
                                    (* 100.0 (dec (/ now
                                                     (:entry trade)))))]
                          (format "%s %s %s%s"
                                  symbol
                                  (if (:executed? trade)
                                    "executed" "pending")
                                  opened
                                  (if pnl
                                    (format " (%+.1f%%)" pnl)
                                    "")))))
                    (:trades st))]
    (when (seq lines)
      (str "Dip trades:\n" (str/join "\n" lines)))))

(defn weekly!
  "Build and send the weekly digest."
  [{:keys [portfolio stocks]}]
  (let [msg (->> [(section "CRYPTO" ".richbot-portfolio-state.edn"
                           (:slots portfolio)
                           #(crypto-price! (:symbol %)) "USDC")
                  (section "STOCKS (modelo)"
                           ".richbot-stocks-state.edn"
                           (:slots stocks)
                           stock-price-eur! "EUR")
                  (dips-section)]
                 (remove nil?)
                 (str/join "\n\n")
                 (str "RESUMEN SEMANAL richbot\n\n"))]
    (println msg)
    (alert/send! msg)))
