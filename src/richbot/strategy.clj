(ns richbot.strategy
  (:require [richbot.indicators :as ind]))

(defn sma-cross-signals
  "Fast/slow SMA crossover with an anti-whipsaw band: the fast SMA
  must clear the slow one by band (a fraction, e.g. 0.005) before a
  signal fires. Returns a seq aligned with candles."
  [{:keys [fast slow band] :or {band 0.0}} candles]
  (let [closes (map :close candles)
        up? (fn [f s] (> f (* s (+ 1.0 band))))
        down? (fn [f s] (< f (* s (- 1.0 band))))
        pairs (map vector (ind/sma fast closes) (ind/sma slow closes))]
    (map (fn [[pf ps] [cf cs]]
           (when (and pf ps cf cs)
             (cond
               (and (not (up? pf ps)) (up? cf cs)) :buy
               (and (not (down? pf ps)) (down? cf cs)) :sell)))
         (cons [nil nil] pairs)
         pairs)))

(defn donchian-signals
  "Channel breakout: buy when close exceeds the previous entry-period
  high; sell when it falls below the previous exit-period low."
  [{:keys [entry exit]} candles]
  (let [closes (map :close candles)
        prev-high (cons nil (ind/rolling-max entry (map :high candles)))
        prev-low (cons nil (ind/rolling-min exit (map :low candles)))]
    (map (fn [c h l]
           (when (and c h l)
             (cond
               (> c h) :buy
               (< c l) :sell)))
         closes prev-high prev-low)))

(defn filtered-donchian-signals
  "Donchian breakout with a regime filter and volatility filter.
  Buys require price above long SMA and ATR/close inside bounds.
  Sells still use Donchian exit so risk can come off."
  [{:keys [entry exit trend min-atr max-atr]
    :or {min-atr 0.0 max-atr 1.0}}
   candles]
  (let [raw (donchian-signals {:entry entry :exit exit} candles)
        trend-sma (ind/sma trend (map :close candles))
        atrs (ind/atr 14 candles)]
    (map (fn [signal {:keys [close]} sma atr]
           (let [atr-pct (when (and atr (pos? close)) (/ atr close))
                 ok-vol? (and atr-pct
                              (<= min-atr atr-pct)
                              (<= atr-pct max-atr))]
             (case signal
               :buy (when (and sma (> close sma) ok-vol?) :buy)
               :sell :sell
               nil)))
         raw candles trend-sma atrs)))

(defn rsi-signals
  "Mean reversion: buy oversold (RSI < buy-th), sell overbought."
  [{:keys [period buy-th sell-th]} candles]
  (map (fn [r]
         (cond
           (nil? r) nil
           (< r buy-th) :buy
           (> r sell-th) :sell))
       (ind/rsi period (map :close candles))))

(def strategies
  "Registry: signal fn, param grid for walk-forward, optional validity."
  {:sma-cross
   {:signals sma-cross-signals
    :grid {:fast [10 20 30] :slow [50 100 150] :band [0.0 0.005]}
    :valid? (fn [{:keys [fast slow]}] (< fast slow))}

   :donchian
   {:signals donchian-signals
    :grid {:entry [20 40 55] :exit [10 20]}}

   :filtered-donchian
   {:signals filtered-donchian-signals
    :grid {:entry [40 55 80]
           :exit [20 30]
           :trend [150 200]
           :min-atr [0.0]
           :max-atr [0.04 0.08]}}

   :rsi-revert
   {:signals rsi-signals
    :grid {:period [14] :buy-th [25 30 35] :sell-th [55 65 70]}}})

(defn signals
  "Compute signals for {:id ... :params ...} over candles."
  [{:keys [id params]} candles]
  (let [f (get-in strategies [id :signals])]
    (assert f (str "Unknown strategy " id))
    (f params candles)))
