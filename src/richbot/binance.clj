(ns richbot.binance
  "Binance REST client: public market data plus signed endpoints for
  the spot testnet (:testnet) and the real exchange (:live)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [richbot.config :as cfg])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.net URLEncoder HttpURLConnection]))

(def ^:private public-url "https://api.binance.com")

(def ^:private venues
  {:testnet {:base "https://testnet.binance.vision"
             :key-env "BINANCE_TESTNET_KEY"
             :secret-env "BINANCE_TESTNET_SECRET"}
   :live {:base public-url
          :key-env "BINANCE_LIVE_KEY"
          :secret-env "BINANCE_LIVE_SECRET"}})

(defn- with-timeouts ^HttpURLConnection [^HttpURLConnection conn]
  (doto conn
    (.setConnectTimeout 10000)
    (.setReadTimeout 30000)))

(defn- read-conn! [^HttpURLConnection conn]
  (let [status (.getResponseCode conn)
        stream (if (< status 400)
                 (.getInputStream conn)
                 (.getErrorStream conn))
        body (slurp stream)]
    (when (>= status 400)
      (throw (ex-info (str "Binance HTTP " status)
                      {:status status :body body})))
    (json/read-str body :key-fn keyword)))

(defn- get-json! [base path query]
  (let [qs (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) query))
        url (java.net.URL. (str base path "?" qs))
        conn (with-timeouts (.openConnection url))]
    (read-conn! conn)))

(defn- hmac-sha256 [secret data]
  (let [key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")
        mac (doto (Mac/getInstance "HmacSHA256") (.init key))]
    (format "%064x"
            (BigInteger. 1 (.doFinal mac (.getBytes data "UTF-8"))))))

(defn- signed-qs [params secret]
  (let [qs (str/join "&" (map (fn [[k v]]
                                (str (name k) "="
                                     (URLEncoder/encode (str v) "UTF-8")))
                              params))]
    (str qs "&signature=" (hmac-sha256 secret qs))))

(defn- signed-get! [base path params api-key secret]
  (let [qs (signed-qs params secret)
        url (java.net.URL. (str base path "?" qs))
        conn (doto (with-timeouts (.openConnection url))
               (.setRequestProperty "X-MBX-APIKEY" api-key))]
    (read-conn! conn)))

(defn- signed-post! [base path params api-key secret]
  (let [body (signed-qs params secret)
        url (java.net.URL. (str base path))
        conn (doto (with-timeouts (.openConnection url))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setRequestProperty "X-MBX-APIKEY" api-key)
               (.setRequestProperty "Content-Type"
                                    "application/x-www-form-urlencoded"))]
    (.connect conn)
    (with-open [out (.getOutputStream conn)]
      (.write out (.getBytes body "UTF-8")))
    (read-conn! conn)))

;; ---------------------------------------------------------------------------
;; Public market data

(defn- ->candle [[open-time open high low close volume close-time]]
  {:open-time open-time
   :open (parse-double open)
   :high (parse-double high)
   :low (parse-double low)
   :close (parse-double close)
   :volume (parse-double volume)
   :close-time close-time})

(defn klines!
  "Fetch up to 1000 candles, oldest first. Last candle is still forming."
  [symbol interval limit]
  (mapv ->candle
        (get-json! public-url "/api/v3/klines"
                   {:symbol symbol :interval interval :limit limit})))

(def ^:private interval-ms
  {"1m" 60000 "5m" 300000 "15m" 900000 "30m" 1800000
   "1h" 3600000 "4h" 14400000 "1d" 86400000})

(defn periods-per-year
  "Candles per year for a Binance interval string."
  [interval]
  (/ 3.15576e10 (interval-ms interval)))

(defn klines-history!
  "Fetch ~n candles ending now, paging in batches of 1000."
  [symbol interval n]
  (let [step (long (interval-ms interval))
        start (- (System/currentTimeMillis) (* (long n) step))]
    (loop [acc [] from start]
      (let [batch (mapv ->candle
                        (get-json! public-url "/api/v3/klines"
                                   {:symbol symbol :interval interval
                                    :startTime from :limit 1000}))
            acc (into acc batch)]
        (if (< (count batch) 1000)
          acc
          (do (Thread/sleep 200)
              (recur acc (inc (long (:close-time (peek batch)))))))))))

(def ^:private quote-assets ["USDT" "USDC" "FDUSD" "EUR"])

(defn split-symbol
  "[base quote] for a spot symbol, e.g. \"BTCUSDC\" -> [\"BTC\" \"USDC\"]."
  [symbol]
  (or (some (fn [q]
              (when (str/ends-with? symbol q)
                [(subs symbol 0 (- (count symbol) (count q))) q]))
            quote-assets)
      (throw (ex-info "Unsupported quote asset" {:symbol symbol}))))

;; ---------------------------------------------------------------------------
;; Exchange filters and quantity rounding

(defn round-step
  "Floor qty to the symbol's step size (a decimal string like
  \"0.00001000\"). Returns a plain decimal string safe for the API."
  [qty step]
  (.. (bigdec qty)
      (divideToIntegralValue (bigdec step))
      (multiply (bigdec step))
      stripTrailingZeros
      toPlainString))

(defn exchange-filters!
  "LOT_SIZE and notional limits for symbol on venue:
  {:step ... :min-qty ... :min-notional ...}."
  [venue symbol]
  (let [base (:base (venues venue))
        info (get-json! base "/api/v3/exchangeInfo" {:symbol symbol})
        by-type (->> (:filters (first (:symbols info)))
                     (map (juxt :filterType identity))
                     (into {}))
        lot (by-type "LOT_SIZE")
        notional (or (by-type "NOTIONAL") (by-type "MIN_NOTIONAL"))]
    {:step (:stepSize lot)
     :min-qty (parse-double (:minQty lot))
     :min-notional (parse-double (:minNotional notional "5.0"))}))

;; ---------------------------------------------------------------------------
;; Signed account endpoints

(defn- creds [venue]
  (let [{:keys [key-env secret-env]} (venues venue)]
    {:key (cfg/require-env! key-env)
     :secret (cfg/require-env! secret-env)}))

(defn balances!
  "Free balances for the given asset names on venue,
  e.g. (balances! :testnet #{\"USDC\" \"BTC\"})."
  [venue assets]
  (let [{:keys [key secret]} (creds venue)
        result (signed-get! (:base (venues venue)) "/api/v3/account"
                            {:timestamp (System/currentTimeMillis)
                             :recvWindow 5000}
                            key secret)]
    (->> (:balances result)
         (filter #(assets (:asset %)))
         (map (fn [b] [(:asset b) (parse-double (:free b))]))
         (into {}))))

(defn market-order!
  "Place a market order on venue. Pass exactly one of
  :qty (base asset amount, pre-rounded string) or
  :spend (quote asset amount string, e.g. USDC to spend)."
  [venue symbol side {:keys [qty spend]}]
  (let [{:keys [key secret]} (creds venue)
        params (cond-> {:symbol symbol :side side :type "MARKET"
                        :timestamp (System/currentTimeMillis)
                        :recvWindow 5000}
                 qty (assoc :quantity qty)
                 spend (assoc :quoteOrderQty spend))]
    (signed-post! (:base (venues venue)) "/api/v3/order"
                  params key secret)))
