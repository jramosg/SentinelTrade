(ns richbot.stocks-data
  "Daily stock/ETF candles and FX rates from the Yahoo Finance
  chart API (free, no key). Candles use the same map shape as
  richbot.binance, so the backtester, walk-forward and DCA logic
  run unchanged. ~252 trading candles per year."
  (:require [clojure.data.json :as json])
  (:import [java.net HttpURLConnection URL]))

(def periods-per-year 252)

(def ^:private base
  "https://query1.finance.yahoo.com/v8/finance/chart/")

(defn- get-json! [url]
  (let [conn ^HttpURLConnection (.openConnection (URL. url))]
    (doto conn
      (.setConnectTimeout 10000)
      (.setReadTimeout 30000)
      ;; Yahoo rejects the default Java agent.
      (.setRequestProperty "User-Agent" "Mozilla/5.0 (richbot)"))
    (let [status (.getResponseCode conn)
          body (slurp (if (< status 400)
                        (.getInputStream conn)
                        (.getErrorStream conn)))]
      (when (>= status 400)
        (throw (ex-info (str "Yahoo HTTP " status)
                        {:status status :url url :body body})))
      (json/read-str body :key-fn keyword))))

(defn- chart! [symbol range-str]
  (let [result (-> (get-json! (str base symbol
                                   "?interval=1d&range=" range-str
                                   "&events=div"))
                   :chart :result first)]
    (when-not result
      (throw (ex-info "Yahoo: no data" {:symbol symbol})))
    result))

(defn- ->candles
  "Build candle maps; when adjust? scale OHLC by adjclose/close so
  dividends are reinvested (fair for total-return DCA backtests)."
  [{:keys [timestamp indicators]} adjust?]
  (let [{:keys [open high low close volume]} (first (:quote indicators))
        adj (:adjclose (first (:adjclose indicators)))]
    (->> (map (fn [t o h l c v a]
                (when (and t o h l c)
                  (let [k (if (and adjust? a (pos? c)) (/ a c) 1.0)]
                    {:open-time (* 1000 (long t))
                     :open (* o k)
                     :high (* h k)
                     :low (* l k)
                     :close (* c k)
                     :volume (or v 0.0)
                     :close-time (* 1000 (long t))})))
              timestamp open high low close volume
              (or adj (repeat nil)))
         (filterv some?))))

(defn daily-candles!
  "~years of daily candles for a Yahoo symbol (e.g. \"SPY\",
  \"VWCE.DE\"), oldest first. The last candle may still be forming
  while its market is open — drop it for signals, exactly like the
  Binance feed. :adjust? true (default) reinvests dividends."
  ([symbol years] (daily-candles! symbol years {:adjust? true}))
  ([symbol years {:keys [adjust?] :or {adjust? true}}]
   (->candles (chart! symbol (str (max 1 (long (Math/ceil years))) "y"))
              adjust?)))

(defn last-price!
  "Latest (possibly delayed) price for a Yahoo symbol."
  [symbol]
  (-> (chart! symbol "5d") :meta :regularMarketPrice))

(defn fx-rate!
  "Units of `to` per one `from`, e.g. (fx-rate! \"EUR\" \"USD\")
  ~ 1.08. Same-currency rate is 1.0."
  [from to]
  (if (= from to)
    1.0
    (last-price! (str from to "=X"))))
