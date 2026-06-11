(ns richbot.backtest
  "Event-loop backtester: all-in/all-out position,
  taker fees and adverse slippage applied to every fill.")

(def default-opts
  {:fee 0.001       ; 0.1% Binance spot taker fee
   :slippage 0.0005 ; 5 bps adverse fill
   :cash 10000.0
   :periods-per-year 8760}) ; 1h candles

(defn- fill-price [side price slippage]
  (case side
    :buy (* price (+ 1.0 slippage))
    :sell (* price (- 1.0 slippage))))

(defn- step [{:keys [fee slippage]} state [candle signal]]
  (let [{:keys [cash qty]} state
        price (:close candle)
        state (cond
                (and (= :buy signal) (zero? qty))
                (let [p (fill-price :buy price slippage)
                      q (/ (* cash (- 1.0 fee)) p)]
                  (-> state
                      (assoc :cash 0.0 :qty q)
                      (update :trades conj {:side :buy :price p :qty q
                                            :time (:open-time candle)})))

                (and (= :sell signal) (pos? qty))
                (let [p (fill-price :sell price slippage)
                      proceeds (* qty p (- 1.0 fee))]
                  (-> state
                      (assoc :cash proceeds :qty 0.0)
                      (update :trades conj {:side :sell :price p :qty qty
                                            :time (:open-time candle)})))

                :else state)]
    (-> state
        (update :equity conj (+ (:cash state) (* (:qty state) price)))
        (cond-> (pos? (:qty state)) (update :in-market inc)))))

(defn- round-trips
  "Per-trade returns of completed buy->sell pairs (fill prices,
  so slippage is included; fees are reflected in equity)."
  [trades]
  (->> (partition 2 trades)
       (map (fn [[{bp :price} {sp :price}]] (/ (- sp bp) bp)))))

(defn max-drawdown [equity]
  (second
   (reduce (fn [[peak mdd] e]
             (let [peak (max peak e)]
               [peak (max mdd (/ (- peak e) peak))]))
           [(first equity) 0.0]
           equity)))

(defn- profit-factor
  "Gross gains divided by gross losses across round trips."
  [trips]
  (let [pos (filter pos? trips)
        neg (filter neg? trips)]
    (when (seq neg)
      (/ (reduce + 0.0 pos)
         (Math/abs (reduce + 0.0 neg))))))

(defn sharpe
  "Annualized Sharpe ratio of per-candle equity returns."
  [equity ppy]
  (let [rets (map #(dec (/ %2 %1)) equity (rest equity))
        n (count rets)]
    (when (pos? n)
      (let [mean (/ (reduce + rets) n)
            sd (Math/sqrt
                (/ (reduce + (map #(Math/pow (- % mean) 2) rets)) n))]
        (when (pos? sd)
          (* (/ mean sd) (Math/sqrt ppy)))))))

(defn run
  "Run signals over candles. Returns {:stats ... :trades ... :equity ...}."
  [candles signals opts]
  (let [{:keys [cash periods-per-year] :as opts} (merge default-opts opts)
        init {:cash cash :qty 0.0 :trades [] :equity [] :in-market 0}
        final (reduce (partial step opts) init (map vector candles signals))
        {:keys [equity trades in-market]} final
        trips (round-trips trades)]
    {:trades trades
     :equity equity
     :stats {:start-cash cash
             :final-equity (peek equity)
             :return (dec (/ (peek equity) cash))
             :buy-hold-return (dec (/ (:close (last candles))
                                      (:close (first candles))))
             :round-trips (count trips)
             :win-rate (when (seq trips)
                         (/ (count (filter pos? trips))
                            (double (count trips))))
             :profit-factor (profit-factor trips)
             :sharpe (sharpe equity periods-per-year)
             :exposure (/ in-market (double (count equity)))
             :max-drawdown (max-drawdown equity)}}))
