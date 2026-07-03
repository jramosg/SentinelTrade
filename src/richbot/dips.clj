(ns richbot.dips
  "Daily watchlist scan: quality names trading at a deep discount
  to their 200-day SMA. Sends one Telegram digest per day with the
  new opportunities. Informational only - ideas for EXTRA money,
  never part of the monthly plan, and it books nothing. Per-symbol
  cooldown so the same discount is not repeated daily; a deeper
  tier re-alerts immediately."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [richbot.alert :as alert]
            [richbot.config :as config]
            [richbot.stocks-data :as sd])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(def ^:private trend 200)
(def ^:private state-file ".richbot-dips-state.edn")

(def ^:private default-buy-tiers
  "Single-stock dip amounts are deliberately smaller than the ETF
  recharge tiers. Deep discounts on individual stocks often mean
  changed fundamentals, not a clean index-style mean reversion."
  [[0.05 150.0]
   [0.09 300.0]
   [0.15 450.0]])

(def ^:private pending-expiry-days
  "A suggested buy not executed within this window is dropped and
  its reserved monthly budget released — an ignored idea must not
  block new ones."
  7)

(def ^:private max-new-idea 300.0)
(def ^:private max-recharge 150.0)
(def ^:private max-crowded 150.0)
(def ^:private default-opportunity-budget 300.0)

(def ^:private default-owned
  "Holdings come from the broker CSV (REVOLUT_STOCKS_CSV) or from
  :owned in config.local.edn."
  #{})

(def ^:private default-crowded-tags
  #{:mega-tech :ai :semis})

(def ^:private theses
  {"MA" {:thesis "payments duopoly, high-margin network, secular card volume"
         :invalidation "regulation, volume slowdown, margin compression"}
   "HD" {:thesis "quality home-improvement franchise, housing/rates cycle"
         :invalidation "housing weakness becomes structural, margins crack"}
   "MC.PA" {:thesis "luxury leader, cyclical China/consumer dip, EUR listing"
            :invalidation "brand desirability or pricing power deteriorates"}
   "MSFT" {:thesis "cloud, enterprise software and AI platform compounder"
           :invalidation "Azure/AI demand slows or margins compress sharply"}
   "META" {:thesis "dominant ads platform with AI improving ad performance"
           :invalidation "ads cycle breaks or AI capex overwhelms returns"}
   "AVGO" {:thesis "AI custom silicon (Google TPUs, Apple chips) and data-centre networking; customer lock-in drives pricing power"
           :invalidation "hyperscaler in-house silicon displaces merchant chips; VMware integration fails"}
   "NVDA" {:thesis "GPU monopoly for AI training and inference; data-centre AI infrastructure backbone with software moat (CUDA)"
           :invalidation "custom ASICs displace GPUs at scale; export controls tighten further"}
   "UBER" {:thesis "global rides and delivery two-sided network; platform leverage converting to free cash flow at scale"
           :invalidation "autonomous vehicles commoditise the network; regulatory pressure on gig model"}})

(def ^:private default-universe
  [{:symbol "AAPL" :label "Apple" :tags #{:mega-tech :consumer-tech}
    :quality 7 :valuation 5}
   {:symbol "ADBE" :label "Adobe" :tags #{:software :ai-risk}
    :quality 7 :valuation 4}
   {:symbol "AMZN" :label "Amazon" :tags #{:mega-tech :cloud :ai}
    :quality 8 :valuation 5}
   {:symbol "ASML" :label "ASML" :tags #{:semis :eu}
    :quality 8 :valuation 5}
   {:symbol "AVGO" :label "Broadcom" :tags #{:semis :ai}
    :quality 9 :valuation 6}
   {:symbol "COST" :label "Costco" :tags #{:consumer-defensive}
    :bonus 2.0 :quality 8 :valuation 3}
   {:symbol "CRM" :label "Salesforce" :tags #{:software :ai-risk}
    :quality 6 :valuation 4}
   {:symbol "GOOG" :label "Alphabet" :tags #{:mega-tech :cloud :ai}
    :quality 8 :valuation 6}
   {:symbol "HD" :label "Home Depot" :tags #{:consumer-cyclical}
    :bonus 1.0 :quality 7 :valuation 6}
   {:symbol "ITX.MC" :label "Inditex" :tags #{:consumer :eu}
    :quality 7 :valuation 5}
   {:symbol "JPM" :label "JPMorgan" :tags #{:financials} :bonus 1.0
    :quality 7 :valuation 6}
   {:symbol "LLY" :label "Eli Lilly" :tags #{:healthcare}
    :quality 8 :valuation 3}
   {:symbol "MA" :label "Mastercard" :tags #{:payments :financials}
    :bonus 8.0 :quality 9 :valuation 6}
   {:symbol "MC.PA" :label "LVMH" :tags #{:luxury :eu} :bonus 3.0
    :quality 8 :valuation 6}
   {:symbol "MELI" :label "MercadoLibre" :tags #{:commerce :latam}
    :quality 7 :valuation 4}
   {:symbol "META" :label "Meta" :tags #{:mega-tech :ads :ai}
    :quality 7 :valuation 6}
   {:symbol "MSFT" :label "Microsoft" :tags #{:mega-tech :cloud :ai}
    :quality 9 :valuation 6}
   {:symbol "NFLX" :label "Netflix" :tags #{:media}
    :quality 6 :valuation 4}
   {:symbol "NKE" :label "Nike" :tags #{:consumer-cyclical}
    :quality 4 :valuation 4}
   {:symbol "NVO" :label "Novo Nordisk" :tags #{:healthcare :eu}
    :quality 7 :valuation 4}
   {:symbol "NVDA" :label "Nvidia" :tags #{:semis :ai}
    :quality 9 :valuation 6}
   {:symbol "SAP" :label "SAP" :tags #{:software :eu :ai-risk}
    :quality 7 :valuation 4}
   {:symbol "TSM" :label "TSMC" :tags #{:semis :ai}
    :quality 8 :valuation 6}
   {:symbol "UBER" :label "Uber" :tags #{:transport :platform}
    :quality 7 :valuation 5}
   {:symbol "UNH" :label "UnitedHealth" :tags #{:healthcare}
    :quality 5 :valuation 5}
   {:symbol "V" :label "Visa" :tags #{:payments :financials} :bonus 4.0
    :quality 9 :valuation 6}
   {:symbol "VWCE.DE" :label "VWCE" :tags #{:world-etf}
    :quality 8 :valuation 7}])

(defn- pct [x] (format "%.0f%%" (* 100.0 (double x))))
(defn- eur [x] (format "%.0f EUR" (double x)))

(defn- html [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))
(defn- today [] (str (LocalDate/now)))
(defn- month-key [day] (subs day 0 7))

(defn- load-state []
  (try (edn/read-string (slurp state-file))
       (catch Exception _ {})))

(defn- save-state! [st]
  (spit state-file (pr-str st)))

(defn- csv-fields [line]
  (str/split line #"," -1))

(defn- normalize-symbol [symbol]
  (case symbol
    "EXI2" "EXI2.DE"
    "VWCE" "VWCE.DE"
    ;; Revolut reports LVMH by its German WKN
    "853292" "MC.PA"
    symbol))

(defn- amount-value [s]
  (when (seq s)
    (parse-double (last (str/split s #"\s+")))))

(defn- portfolio-csv-path [{:keys [portfolio-csv]}]
  (or portfolio-csv
      (not-empty (get config/env "REVOLUT_STOCKS_CSV"))))

(defn- fundamentals-csv-path [{:keys [fundamentals-csv]}]
  (or fundamentals-csv
      (not-empty (get config/env "STOCKS_FUNDAMENTALS_CSV"))
      (when (.exists (java.io.File. "fundamentals.csv"))
        "fundamentals.csv")))

(defn- timesfm-signals-path [{:keys [timesfm-signals]}]
  (or timesfm-signals
      (not-empty (get config/env "TIMESFM_SIGNALS_JSON"))
      "resources/timesfm_signals.json"))

(def ^:private signals-max-age-days
  "A 21-day forecast generated more than this many days ago is stale
  and ignored — better no tilt than a misleading one. The refresh
  script must rsync a fresh JSON within this window."
  5)

(defn- instant->date [^java.time.Instant inst]
  (-> inst (.atZone (java.time.ZoneId/systemDefault)) .toLocalDate))

(defn- signals-age-days
  "Age in days from the embedded `_generated_at` (robust when the file
  is rsynced and mtime is not preserved), falling back to file mtime."
  [signals ^java.io.File f today]
  (let [today* (LocalDate/parse today)
        from-meta (when-let [ts (get signals "_generated_at")]
                    (try (-> (java.time.Instant/parse ts) instant->date)
                         (catch Exception _ nil)))
        date (or from-meta
                 (instant->date (java.time.Instant/ofEpochMilli
                                 (.lastModified f))))]
    (.between ChronoUnit/DAYS date today*)))

(defn- load-timesfm-signals
  "Load forecast signals, but only when fresh — a stale or unreadable
  file yields nil so the scorer simply runs without the tilt."
  [cfg today]
  (let [file (java.io.File. (timesfm-signals-path cfg))]
    (try
      (when (.exists file)
        (let [signals (json/read-str (slurp file))
              age (signals-age-days signals file today)]
          (if (> age signals-max-age-days)
            (do (println "DIPS signals stale -" age "days old; ignoring")
                nil)
            signals)))
      (catch Exception e
        (println "DIPS signals unreadable -" (ex-message e))
        nil))))

(defn- csv-rows [path]
  (when (and path (.exists (java.io.File. path)))
    (let [[header & lines] (str/split-lines (slurp path))
          ks (mapv keyword (csv-fields header))]
      (map #(zipmap ks (csv-fields %)) lines))))

(defn- portfolio-positions [path]
  (when-let [rows (seq (csv-rows path))]
    (reduce (fn [positions row]
              (let [ticker (:Ticker row)
                    type (:Type row)
                    quantity (:Quantity row)
                    total-amount ((keyword "Total Amount") row)]
                (if (str/blank? ticker)
                       positions
                       (let [symbol (normalize-symbol ticker)
                             qty (if (str/blank? quantity)
                                   0.0
                                   (parse-double quantity))
                             amount (or (amount-value total-amount)
                                        0.0)]
                         (cond
                           (str/starts-with? type "BUY")
                           (-> positions
                               (update-in [symbol :qty] (fnil + 0.0) qty)
                               (update-in [symbol :cost] (fnil + 0.0)
                                          amount))

                           (str/starts-with? type "SELL")
                           (-> positions
                               (update-in [symbol :qty] (fnil - 0.0) qty)
                               (update-in [symbol :cost] (fnil - 0.0)
                                          amount))

                           :else positions)))))
            {} rows)))

(defn- csv-owned [cfg]
  (->> (portfolio-positions (portfolio-csv-path cfg))
       (filter (fn [[_ {:keys [qty]}]] (> qty 1.0E-8)))
       (map key)
       set))

(defn- owned-set [{:keys [owned] :as cfg}]
  (let [csv (csv-owned cfg)]
    (if (seq csv)
      csv
      (or owned default-owned))))

(defn- symbol-currency [symbol]
  (if (or (str/ends-with? symbol ".DE")
          (str/ends-with? symbol ".PA")
          (str/ends-with? symbol ".MC"))
    "EUR"
    "USD"))

(defn- price-eur! [symbol]
  (let [price (sd/last-price! symbol)
        fx (sd/fx-rate! (symbol-currency symbol) "EUR")]
    (* price fx)))

(defn- portfolio-snapshot! [cfg universe]
  (let [positions (portfolio-positions (portfolio-csv-path cfg))
        values (->> positions
                    (keep (fn [[symbol {:keys [qty cost]}]]
                            (when (> qty 1.0E-8)
                              (try
                                [symbol (* qty (price-eur! symbol))]
                                (catch Exception _
                                  [symbol (max 0.0 cost)])))))
                    (into {}))
        total (reduce + 0.0 (vals values))
        tags-by-symbol (into {} (map (juxt :symbol :tags) universe))
        tag-values (reduce (fn [m [symbol value]]
                             (reduce #(update %1 %2 (fnil + 0.0) value)
                                     m (get tags-by-symbol symbol #{})))
                           {} values)]
    {:values values
     :total total
     :weights (if (pos? total)
                (update-vals values #(/ % total))
                {})
     :tag-weights (if (pos? total)
                    (update-vals tag-values #(/ % total))
                    {})}))

(defn- buy-transactions [cfg]
  (->> (csv-rows (portfolio-csv-path cfg))
       (keep (fn [row]
               (let [ticker (:Ticker row)
                     type (:Type row)
                     quantity (:Quantity row)
                     total-amount ((keyword "Total Amount") row)]
                 (when (and (seq ticker)
                            (str/starts-with? type "BUY"))
                   {:date (subs (:Date row) 0 10)
                    :symbol (normalize-symbol ticker)
                    :qty (if (str/blank? quantity)
                           0.0
                           (parse-double quantity))
                    :amount (or (amount-value total-amount) 0.0)}))))))

(defn- matching-buy [buys symbol opened]
  (->> buys
       (filter #(and (= symbol (:symbol %))
                     (or (nil? opened)
                         (not (neg? (compare (:date %) opened))))))
       (sort-by :date)
       first))

(defn- parse-num [s]
  (when (seq s)
    (parse-double s)))

(defn- parse-int [s]
  (when (seq s)
    (parse-long s)))

(defn- fundamentals-overrides [cfg]
  (->> (csv-rows (fundamentals-csv-path cfg))
       (keep (fn [row]
               (when-let [symbol (not-empty (:symbol row))]
                 (let [quality (parse-int (:quality row))
                       valuation (parse-int (:valuation row))
                       pe (parse-num (:pe row))
                       fcf-yield (parse-num (:fcf_yield row))
                       revenue-growth (parse-num (:revenue_growth row))
                       eps-revisions (parse-num (:eps_revisions row))]
                   [(normalize-symbol symbol)
                    (cond-> {}
                      quality (assoc :quality quality)
                      valuation (assoc :valuation valuation)
                      pe (assoc :pe pe)
                      fcf-yield (assoc :fcf-yield fcf-yield)
                      revenue-growth (assoc :revenue-growth
                                            revenue-growth)
                      eps-revisions (assoc :eps-revisions
                                           eps-revisions)
                      (seq (:updated row)) (assoc :fundamentals-updated
                                                  (:updated row))
                      (seq (:notes row)) (assoc :fundamentals-notes
                                                (:notes row)))]))))
       (into {})))

(defn- amount-for [tiers discount]
  (->> tiers
       (filter #(>= discount (first %)))
       (map second)
       (reduce max 0.0)))

(defn- round-eur [x]
  (* 50.0 (Math/round (/ (double x) 50.0))))

(defn- cap-amount [class amount]
  (min amount
       (case class
         :recharge max-recharge
         :crowded max-crowded
         :new-idea max-new-idea
         0.0)))

(defn- crowded? [crowded-tags {:keys [tags]}]
  (boolean (seq (set/intersection crowded-tags (or tags #{})))))

(defn- watchlist [{:keys [symbols universe] :as cfg}]
  (let [overrides (fundamentals-overrides cfg)
        rows (or universe
                 (when (seq symbols)
                   (mapv (fn [symbol] {:symbol symbol}) symbols))
                 default-universe)]
    (mapv #(merge % (get overrides (:symbol %) {})) rows)))

(defn- stats! [symbol]
  (let [closes (mapv :close (butlast (sd/daily-candles! symbol 2)))]
    (when (> (count closes) trend)
      (let [signal-close (peek closes)
            live-price (or (sd/last-price! symbol) signal-close)
            sma (/ (reduce + (take-last trend closes)) trend)
            year (vec (take-last 252 closes))]
        {:symbol symbol
         :price live-price
         :signal-close signal-close
         :sma200 sma
         :discount (- 1.0 (/ live-price sma))
         :signal-discount (- 1.0 (/ signal-close sma))
         :off-high (- 1.0 (/ live-price (reduce max year)))
         :ret-1y (dec (/ live-price (first year)))}))))

(defn- tier-of [tiers discount]
  (some->> (seq (filter #(>= discount %) tiers))
           (reduce max)))

(defn- days-since [day today]
  (if day
    (.between ChronoUnit/DAYS
              (LocalDate/parse day) (LocalDate/parse today))
    Long/MAX_VALUE))

(defn- fires
  "Rows whose discount reaches a tier that is either deeper than
  the last alerted one or past the cooldown."
  [{:keys [tiers cooldown-days]
    :or {tiers [0.10 0.15 0.20] cooldown-days 30}}
   today st rows]
  (for [{:keys [symbol discount] :as row} rows
        :let [tier (some->> discount (tier-of tiers))
              prev (or (get-in st [:alerts symbol])
                       (get st symbol))]
        :when (and tier
                   (or (> tier (:tier prev 0.0))
                       (>= (days-since (:day prev) today)
                           cooldown-days)))]
    (assoc row :tier tier)))

(defn- clamp [lo hi x] (max lo (min hi x)))

(defn- timesfm-adjustment
  "Score tilt from the TimesFM 21-day forecast. The point forecast
  scales the tilt continuously (so small forecasts still count, not a
  ±3% cliff), clamped to ±5 and damped when the quantile band is wide
  (an uncertain forecast carries less weight). A fat negative downside
  quantile adds a falling-knife penalty even when the median is benign
  — the classic value-trap shape."
  [{:keys [timesfm-return timesfm-q-low timesfm-q-high]}]
  (if (nil? timesfm-return)
    0.0
    (let [width (when (and timesfm-q-low timesfm-q-high)
                  (- timesfm-q-high timesfm-q-low))
          conf (if (and width (pos? width))
                 (clamp 0.3 1.0 (/ 0.20 width))
                 1.0)
          tilt (* conf (clamp -5.0 5.0 (* 120.0 timesfm-return)))
          knife (if (and timesfm-q-low (< timesfm-q-low -0.15)) -4.0 0.0)]
      (+ tilt knife))))

(defn- score
  [{:keys [discount off-high ret-1y held? crowded? bonus
           quality valuation position-weight tag-weight pe
           fcf-yield revenue-growth eps-revisions] :as row}]
  (cond-> (+ (* 100.0 discount)
             (* 20.0 off-high)
             (* 1.5 (or quality 5.0))
             (or valuation 5.0)
             (or bonus 0.0)
             (timesfm-adjustment row))
    (neg? ret-1y) (+ (* 15.0 ret-1y))
    held? (- 5.0)
    crowded? (- 4.0)
    (> (or position-weight 0.0) 0.08) (- 6.0)
    (> (or tag-weight 0.0) 0.35) (- 5.0)
    (some-> pe (> 45.0)) (- 5.0)
    (some-> fcf-yield (< 0.02)) (- 5.0)
    (some-> revenue-growth (< 0.0)) (- 5.0)
    (some-> eps-revisions (< 0.0)) (- 4.0)
    (< ret-1y -0.25) (- 30.0)
    (< discount 0.05) (- 20.0)))

(defn- exit-rule
  [{:keys [price sma200 discount ret-1y]}]
  (cond
    (< discount 0.05)
    (format "no buy; wait for at least 5%% below SMA200 %.2f"
            (double sma200))

    (< ret-1y -0.25)
    (format "no buy; wait for price back above SMA200 %.2f and 1y trend to improve"
            (double sma200))

    :else
    (let [target-mult (if (>= discount 0.10) 1.20 1.15)
          gain-target (* price target-mult)
          trend-target (if (>= discount 0.10)
                         (* sma200 0.98)
                         sma200)]
      (format (str "review at %.2f (SMA200%s) or %.2f "
                   "(%+.0f%% from buy %.2f); not auto-sell")
              trend-target
              (if (>= discount 0.10) " -2%" "")
              gain-target
              (* 100.0 (dec target-mult))
              price))))

(defn- exit-targets [{:keys [price sma200 discount]}]
  (let [target-mult (if (>= discount 0.10) 1.20 1.15)]
    {:trend-target (if (>= discount 0.10) (* sma200 0.98) sma200)
     :gain-target (* price target-mult)
     :risk-target (* price 0.90)}))

(defn- risk-rule [{:keys [price]}]
  (format "recheck thesis before adding if it falls to %.2f (-10%%)"
          (* price 0.90)))

(defn- why
  [{:keys [discount off-high ret-1y held? crowded? tags
           position-weight tag-weight pe fcf-yield revenue-growth
           eps-revisions timesfm-return timesfm-q-low timesfm-q-high]}]
  (str/join
   "; "
   (cond-> [(str (pct discount) " below SMA200")
            (str (pct off-high) " off 52w high")
            (format "1y %+.0f%%" (* 100.0 ret-1y))]
     (not held?) (conj "not currently owned")
     held? (conj "already owned, size reduced")
     (> (or position-weight 0.0) 0.08)
     (conj (format "position already %.0f%% of portfolio"
                   (* 100.0 position-weight)))
     (> (or tag-weight 0.0) 0.35)
     (conj (format "tag exposure already %.0f%%"
                   (* 100.0 tag-weight)))
     crowded? (conj "crowded tech/AI exposure, size reduced")
     (contains? tags :payments) (conj "adds payments/financials")
     (contains? tags :eu) (conj "EUR-listed/no USD trade for you")
     pe (conj (format "PE %.1f" (double pe)))
     fcf-yield (conj (format "FCF yield %.1f%%"
                             (* 100.0 fcf-yield)))
     revenue-growth (conj (format "rev growth %.1f%%"
                                  (* 100.0 revenue-growth)))
     eps-revisions (conj (format "EPS revisions %.1f%%"
                                 (* 100.0 eps-revisions)))
     timesfm-return (conj (if (and timesfm-q-low timesfm-q-high)
                            (format "TimesFM %+.1f%% 21d [%+.0f..%+.0f%%]"
                                    (* 100.0 timesfm-return)
                                    (* 100.0 timesfm-q-low)
                                    (* 100.0 timesfm-q-high))
                            (format "TimesFM %+.1f%% 21d"
                                    (* 100.0 timesfm-return)))))))

(defn- classify
  [{:keys [discount ret-1y quality valuation held? crowded? pe
           fcf-yield revenue-growth]}]
  (cond
    (< discount 0.05) :watch
    (< ret-1y -0.25) :trap
    (< (or quality 5.0) 5.0) :trap
    (< (or valuation 5.0) 4.0) :watch
    (some-> pe (> 60.0)) :watch
    (some-> fcf-yield (< 0.0)) :trap
    (some-> revenue-growth (< -0.05)) :trap
    held? :recharge
    crowded? :crowded
    :else :new-idea))

(defn- recommendation
  [{:keys [buy-tiers owned crowded-tags portfolio]
    :or {buy-tiers default-buy-tiers
         owned default-owned
         crowded-tags default-crowded-tags}}
   row]
  (let [owned (or owned default-owned)
        thesis (get theses (:symbol row))
        row (merge thesis row)
        held? (contains? owned (:symbol row))
        position-weight (get-in portfolio [:weights (:symbol row)] 0.0)
        tag-weight (->> (:tags row)
                        (map #(get-in portfolio [:tag-weights %] 0.0))
                        (reduce max 0.0))
        row (assoc row :position-weight position-weight
                   :tag-weight tag-weight)
        crowded? (crowded? crowded-tags row)
        class (classify (assoc row :held? held? :crowded? crowded?))
        amount (if (= :trap class)
                 0.0
                 (->> (cond-> (amount-for buy-tiers (:discount row))
                        held? (* 0.5)
                        crowded? (* 0.5))
                      (#(if (> position-weight 0.08) (* % 0.5) %))
                      (#(if (> tag-weight 0.35) (* % 0.75) %))
                      (cap-amount class)))]
    (-> row
        (assoc :held? held?
               :crowded? crowded?
               :class class
               :amount (round-eur amount)
               :why (why (assoc row :held? held? :crowded? crowded?))
               :risk (risk-rule row))
        (assoc :score (score (assoc row :held? held?
                                    :crowded? crowded?)))
        (assoc :exit (exit-rule row)))))

(declare fundamentals-line)

(defn- line
  [{:keys [symbol label price signal-close sma200 class allocated exit why risk
           thesis invalidation] :as row}]
  (format (str "%s (%s) now %.2f | signal close %.2f | "
               "accion: BUY ~%.0f EUR | tipo: %s\n"
               "why: %s | SMA200 %.2f\n"
               "%s"
               "thesis: %s\n"
               "invalidacion: %s\n"
               "review: %s\n"
               "risk: %s")
          (or label symbol) symbol (double price)
          (double signal-close) (double allocated) (name class)
          why (double sma200)
          (if-let [f (fundamentals-line row)] (str f "\n") "")
          (or thesis "quality dip; thesis metadata pending")
          (or invalidation "fundamentals or valuation deteriorate")
          exit risk))

(defn- html-line
  [{:keys [symbol label price signal-close sma200 class allocated exit why
           risk thesis invalidation] :as row}]
  (str "<b>" (html (or label symbol)) "</b> "
       "<code>" (html symbol) "</code> "
       "now <code>" (format "%.2f" (double price)) "</code>"
       " · signal close <code>" (format "%.2f" (double signal-close))
       "</code>\n"
       "Accion: <b>BUY " (html (eur allocated)) "</b> "
       "<i>" (html (name class)) "</i>\n"
       "SMA200: <code>" (format "%.2f" (double sma200)) "</code>\n"
       "Why: " (html why) "\n"
       (when-let [f (fundamentals-line row)]
         (str (html f) "\n"))
       "Thesis: " (html (or thesis "quality dip; thesis metadata pending"))
       "\n"
       "Invalidacion: "
       (html (or invalidation "fundamentals or valuation deteriorate"))
       "\n"
       "Review: " (html exit) "\n"
       "Risk: " (html risk)))

(defn- watch-line [{:keys [symbol label amount score]}]
  (format "%s (%s) ~%.0f EUR score %.1f - wait; budget/correlation"
          (or label symbol) symbol (double amount) (double score)))

(defn- html-watch-line [{:keys [symbol label amount score]}]
  (str "• " (html (or label symbol)) " "
       "<code>" (html symbol) "</code> "
       "<code>" (html (eur amount)) "</code> "
       "score " (format "%.1f" (double score))))

(defn- fundamentals-line
  [{:keys [pe fcf-yield revenue-growth eps-revisions
           fundamentals-updated]}]
  (when (or pe fcf-yield revenue-growth eps-revisions)
    (str "Fundamentals: "
         (str/join
          ", "
          (cond-> []
            pe (conj (format "PE %.1f" (double pe)))
            fcf-yield (conj (format "FCF %.1f%%"
                                    (* 100.0 fcf-yield)))
            revenue-growth (conj (format "rev %.1f%%"
                                         (* 100.0 revenue-growth)))
            eps-revisions (conj (format "EPS rev %.1f%%"
                                        (* 100.0 eps-revisions)))
            fundamentals-updated (conj (str "updated "
                                            fundamentals-updated)))))))

(defn- trade-plan
  [{:keys [allocated price signal-close sma200 exit risk thesis invalidation]
    :as row}]
  {:amount allocated
   :entry price
   :signal-close signal-close
   :sma200 sma200
   :targets (exit-targets row)
   :review exit
   :risk risk
   :thesis thesis
   :invalidation invalidation})

(defn- buyable? [{:keys [amount]}]
  (pos? amount))

(defn- correlated? [a b]
  (boolean (seq (set/intersection (:tags a #{}) (:tags b #{})))))

(defn- allocate
  [{:keys [opportunity-budget]
    :or {opportunity-budget default-opportunity-budget}}
   day st rows]
  (let [month (month-key day)
        spent (if (= month (get-in st [:budget :month]))
                (get-in st [:budget :spent] 0.0)
                0.0)]
    (loop [remaining (max 0.0 (- opportunity-budget spent))
         selected []
         [row & more] (sort-by (juxt (comp - :score) :symbol) rows)]
      (if-not row
        selected
        (let [amount (:amount row)]
          (if (and (pos? amount)
                   (<= amount remaining)
                   (not-any? #(correlated? row %) selected))
            (recur (- remaining amount)
                   (conj selected (assoc row :allocated amount))
                   more)
            (recur remaining selected more)))))))

(defn- merge-timesfm [signals row]
  (if-let [sig (get signals (:symbol row))]
    (assoc row :timesfm-return (get sig "return")
           :timesfm-q-low (get sig "q_low")
           :timesfm-q-high (get sig "q_high"))
    row))

(defn- annotate-owned [cfg]
  (let [universe (watchlist cfg)]
    (assoc cfg
           :owned (owned-set cfg)
           :portfolio (portfolio-snapshot! cfg universe))))

(defn- summarize [rows class]
  (->> rows
       (filter #(= class (:class %)))
       (take 4)
       (map :symbol)
       (str/join ", ")))

(defn- review-trigger [price {:keys [targets]}]
  (cond
    (>= price (:trend-target targets)) :trend-review
    (>= price (:gain-target targets)) :gain-review
    (<= price (:risk-target targets)) :risk-review
    :else nil))

(defn- review-line [symbol price trigger trade]
  (format "%s %.2f triggered %s | entry %.2f | %s"
          symbol (double price) (name trigger)
          (double (:entry trade))
          (case trigger
            :risk-review (:risk trade)
            (:review trade))))

(defn- review-active-trades! [st]
  (let [alerts
        (keep (fn [[symbol {:keys [trade last-trigger]}]]
                (when (and trade (:executed? trade))
                  (try
                    (let [price (sd/last-price! symbol)
                          trigger (review-trigger price trade)]
                      (when (and trigger (not= trigger last-trigger))
                        {:symbol symbol
                         :price price
                         :trigger trigger
                         :trade trade}))
                    (catch Exception e
                      (println "DIPS REVIEW SKIP" symbol "-"
                               (ex-message e))
                      nil))))
              (:trades st))]
    (when (seq alerts)
      (let [msg (str "RICHBOT STOCKS - DIP REVIEW\n"
                     (str/join "\n"
                               (map #(review-line (:symbol %)
                                                  (:price %)
                                                  (:trigger %)
                                                  (:trade %))
                                    alerts)))]
        (println msg)
        (alert/send! msg)))
    (reduce (fn [st {:keys [symbol trigger]}]
              (assoc-in st [:trades symbol :last-trigger] trigger))
            st alerts)))

(defn- reconcile-trades [cfg st]
  (let [buys (buy-transactions cfg)]
    (reduce (fn [st [symbol {:keys [opened trade]}]]
              (if (and trade (not (:executed? trade)))
                (if-let [buy (matching-buy buys symbol opened)]
                  (-> st
                      (assoc-in [:trades symbol :trade :executed?] true)
                      (assoc-in [:trades symbol :trade :executed-date]
                                (:date buy))
                      (assoc-in [:trades symbol :trade :executed-amount]
                                (:amount buy))
                      (assoc-in [:trades symbol :trade :executed-qty]
                                (:qty buy)))
                  st)
                st))
            st (:trades st))))

(defn- expire-pending!
  "Drop pending dip ideas older than the expiry window. Releases
  their reserved budget when it was booked in the current budget
  month, so the next scan can propose new ideas."
  [today st]
  (let [expired (filter (fn [[_ {:keys [opened trade]}]]
                          (and trade
                               (not (:executed? trade))
                               (>= (days-since opened today)
                                   pending-expiry-days)))
                        (:trades st))]
    (if (empty? expired)
      st
      (do
        (alert/send!
         (str "RICHBOT STOCKS - ideas de dip caducadas sin ejecutar"
              " (presupuesto liberado): "
              (str/join ", " (map key expired))))
        (reduce (fn [st [symbol {:keys [opened trade]}]]
                  (cond-> (update st :trades dissoc symbol)
                    (= (month-key opened)
                       (get-in st [:budget :month]))
                    (update-in [:budget :spent]
                               #(max 0.0 (- (or % 0.0)
                                            (:amount trade))))))
                st expired)))))

(defn- pending-line [symbol {:keys [opened trade]}]
  (format "%s pending execution since %s | planned %.0f EUR"
          symbol opened (double (:amount trade))))

(defn- pending-execution-alert! [st]
  (let [pending (keep (fn [[symbol {:keys [trade] :as row}]]
                        (when (and trade (not (:executed? trade)))
                          (pending-line symbol row)))
                      (:trades st))]
    (when (seq pending)
      (let [msg (str "RICHBOT STOCKS - PENDING EXECUTION\n"
                     (str/join "\n" pending))]
        (println msg)
        (alert/send! msg))))
  st)

(defn scan!
  "Scan the watchlist, send one digest with the symbols at a new or
  re-eligible discount tier, and return the updated per-symbol
  state {\"SYM\" {:tier t :day \"2026-06-11\"}}."
  [cfg today st]
  (let [cfg (annotate-owned cfg)
        st (->> st
                (reconcile-trades cfg)
                (expire-pending! today)
                review-active-trades!
                pending-execution-alert!)
        signals (load-timesfm-signals cfg today)
        watchlist (watchlist cfg)
        rows (keep (fn [{:keys [symbol] :as meta}]
                     (try
                       (some-> (stats! symbol)
                               (merge meta)
                               (->> (merge-timesfm signals)))
                          (catch Exception e
                            (println "DIPS SKIP" symbol "-"
                                     (ex-message e))
                            nil)))
                   watchlist)
        rows (map #(recommendation cfg %) rows)
        hits (sort-by (juxt (comp - :score) :symbol)
                      (fires cfg today st rows))
        buy-hits (filter buyable? hits)
        allocated (allocate cfg today st buy-hits)
        allocated-symbols (set (map :symbol allocated))
        watch (remove #(contains? allocated-symbols (:symbol %)) buy-hits)]
    (when (seq allocated)
      (let [msg (str "RICHBOT STOCKS - ACTION REQUIRED. "
                     "Best use of next EXTRA money; monthly plan untouched. "
                     "Review levels are not automatic sells.\n"
                     (str/join "\n" (map line allocated))
                     (when (seq watch)
                       (str "\nWATCHLIST - do not buy now:\n"
                            (str/join "\n" (map watch-line (take 3 watch)))))
                     (let [rejected (summarize hits :trap)]
                       (when (seq rejected)
                         (str "\nREJECTED traps: " rejected))))
            html-msg (str "<b>RICHBOT STOCKS - ACTION REQUIRED</b>\n"
                          "<i>Best use of next EXTRA money. "
                          "Monthly plan untouched.</i>\n\n"
                          (str/join "\n\n" (map html-line allocated))
                          (when (seq watch)
                            (str "\n\n<b>WATCHLIST - do not buy now</b>\n"
                                 (str/join "\n"
                                           (map html-watch-line
                                                (take 3 watch)))))
                          (let [rejected (summarize hits :trap)]
                            (when (seq rejected)
                              (str "\n\n<b>REJECTED traps</b>: "
                                   (html rejected)))))]
        (println msg)
        (alert/send-html! html-msg)))
    (let [st (cond-> st
               ;; month rollover: last month's spend must not carry
               ;; into the new month's budget
               (not= (month-key today) (get-in st [:budget :month]))
               (assoc :budget {:month (month-key today) :spent 0.0}))]
      (reduce (fn [st {:keys [symbol tier] :as row}]
                (-> st
                    (assoc-in [:alerts symbol] {:tier tier :day today})
                    (assoc-in [:trades symbol :trade] (trade-plan row))
                    (assoc-in [:trades symbol :opened] today)
                    (update-in [:budget :spent] (fnil + 0.0)
                               (:allocated row))))
              st allocated))))

(defn run-scan!
  "Load persisted dip state, scan once, send Telegram if needed,
  and save updated alert/trade/budget state."
  [cfg]
  (let [st (scan! cfg (today) (load-state))]
    (save-state! st)
    st))

(defn status!
  "Print persisted dip-trade status reconciled against the Revolut CSV."
  [cfg]
  (let [cfg (annotate-owned cfg)
        st (reconcile-trades cfg (load-state))]
    (save-state! st)
    (println "symbol,status,opened,entry,now,pnl,amount,review")
    (doseq [[symbol {:keys [opened trade]}] (sort-by key (:trades st))
            :when trade
            :let [now (try (sd/last-price! symbol)
                           (catch Exception _ nil))
                  pnl (when (and now (:entry trade))
                        (dec (/ now (:entry trade))))]]
      (println (str symbol ","
                    (if (:executed? trade) "executed" "pending") ","
                    opened ","
                    (format "%.2f" (double (:entry trade))) ","
                    (if now (format "%.2f" (double now)) "n/a") ","
                    (if pnl (pct pnl) "n/a") ","
                    (format "%.0f" (double (:amount trade))) ","
                    (:review trade))))
    st))

(defn- backtest-trades [candles]
  (loop [rows (drop trend candles)
         hist (vec (take trend candles))
         pos nil
         trades []]
    (if-let [{:keys [close] :as candle} (first rows)]
      (let [sma (/ (reduce + (map :close (take-last trend hist))) trend)
            discount (- 1.0 (/ close sma))
            pos-age (some-> pos :age inc)
            pos (some-> pos (assoc :age pos-age))
            exit? (and pos
                       (or (>= close (:trend-target pos))
                           (>= close (:gain-target pos))
                           (>= (:age pos) 252)))
            enter? (and (nil? pos)
                        (>= discount 0.05))
            pos2 (cond
                   exit? nil
                   enter? {:entry close
                           :trend-target sma
                           :gain-target (* close 1.15)
                           :age 0}
                   :else pos)
            trades2 (if exit?
                      (conj trades (dec (/ close (:entry pos))))
                      trades)]
        (recur (rest rows) (conj hist candle) pos2 trades2))
      trades)))

(defn- avg [xs]
  (when (seq xs) (/ (reduce + xs) (count xs))))

(defn backtest!
  "Very simple sanity check for the dip rule: enter at >=5% below
  SMA200, review/exit at SMA200, +15%, or one trading year."
  [{:keys [symbols years] :or {years 10}}]
  (let [symbols (or symbols ["MA" "HD" "MC.PA" "MSFT" "META"])]
    (println "symbol,trades,avg-return,win-rate")
    (doseq [symbol symbols
            :let [candles (sd/daily-candles! symbol years)
                  trades (backtest-trades candles)
                  wins (filter pos? trades)]]
      (println (str symbol ","
                    (count trades) ","
                    (if-let [r (avg trades)] (pct r) "n/a") ","
                    (if (seq trades)
                      (pct (/ (count wins) (count trades)))
                      "n/a"))))))

(defn fundamentals-template!
  "Print a CSV template for optional manual/live fundamentals."
  [_cfg]
  (println (str "symbol,quality,valuation,pe,fcf_yield,"
                "revenue_growth,eps_revisions,updated,notes"))
  (doseq [{:keys [symbol quality valuation]} default-universe]
    (println (str symbol ","
                  (or quality "") ","
                  (or valuation "") ",,,,,,"))))

(defn report!
  "Scan the full dip universe now and print all rows sorted by
  portfolio-aware score. Does not send Telegram or update state."
  [cfg]
  (let [cfg (annotate-owned cfg)
        signals (load-timesfm-signals cfg (today))
        watchlist (watchlist cfg)
        rows (keep (fn [{:keys [symbol] :as meta}]
                     (try
                       (when-let [row (stats! symbol)]
                         (->> (merge row meta)
                              (merge-timesfm signals)
                              (recommendation cfg)))
                       (catch Exception e
                         (println "DIPS SKIP" symbol "-"
                                  (ex-message e))
                         nil)))
                   watchlist)]
    (println (str "symbol,price,signal-close,sma200,discount,off-high,"
                  "ret-1y,quality,valuation,pe,fcf-yield,rev-growth,"
                  "eps-revisions,class,score,buy-eur,exit"))
    (doseq [{:keys [symbol price signal-close sma200 discount off-high
                    ret-1y quality valuation pe fcf-yield revenue-growth
                    eps-revisions class score amount exit]}
            (sort-by (juxt (comp - :score) :symbol) rows)]
      (println (str symbol ","
                    (format "%.2f" (double price)) ","
                    (format "%.2f" (double signal-close)) ","
                    (format "%.2f" (double sma200)) ","
                    (pct discount) ","
                    (pct off-high) ","
                    (pct ret-1y) ","
                    (or quality "") ","
                    (or valuation "") ","
                    (or pe "") ","
                    (or fcf-yield "") ","
                    (or revenue-growth "") ","
                    (or eps-revisions "") ","
                    (name class) ","
                    (format "%.1f" (double score)) ","
                    (format "%.0f" (double amount)) ","
                    exit)))
    rows))
