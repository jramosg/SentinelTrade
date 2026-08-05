(ns richbot.swing-alerts-test
  (:require [clojure.test :refer [deftest is testing]]
            [richbot.swing-alerts :as swing])
  (:import [java.time ZonedDateTime]))

(def ^:private setup
  {:id :amd
   :symbol "AMD"
   :capital 300.0
   :entry-start "2026-08-05"
   :entry-end "2026-08-05"
   :not-before "10:00"
   :not-after "11:30"
   :session-open-min 460.0
   :session-open-max 500.0
   :entry {:min 475.0
           :trigger 480.0
           :max 485.0
           :confirm-bars 3}
   :stop 418.0
   :targets [{:price 540.0 :fraction 0.5}
             {:price 575.0 :fraction 0.5}]
   :expires "2026-08-12"})

(defn- instant [local]
  (-> (ZonedDateTime/parse
       (str local "-04:00[America/New_York]"))
      .toInstant))

(defn- candle [local open close]
  {:open-time (.toEpochMilli (instant local))
   :open open
   :high (max open close)
   :low (min open close)
   :close close
   :volume 1000.0})

(defn- entry-candles
  ([] (entry-candles 470.0 483.0))
  ([session-open current]
   [(candle "2026-08-05T09:30" session-open 478.0)
    (candle "2026-08-05T09:45" 479.0 481.0)
    (candle "2026-08-05T09:50" 481.0 482.0)
    (candle "2026-08-05T09:55" 482.0 483.0)
    (candle "2026-08-05T10:00" 483.0 current)]))

(deftest requires-three-closed-confirmation-bars
  (testing "a valid opening and three closes above the trigger alert once"
    (let [{:keys [state events]}
          (swing/evaluate-setup setup nil (entry-candles)
                                (instant "2026-08-05T10:01"))]
      (is (= :watching (:status state)))
      (is (= [:entry] (mapv :type events)))
      (is (= 483.0 (:entry-price state)))))

  (testing "a current price above the maximum is not chased"
    (let [{:keys [state events]}
          (swing/evaluate-setup setup nil (entry-candles 470.0 486.0)
                                (instant "2026-08-05T10:01"))]
      (is (= :armed (:status state)))
      (is (empty? events)))))

(deftest rejects-an-opening-outside-the-plan
  (let [{:keys [state events]}
        (swing/evaluate-setup setup nil (entry-candles 501.0 483.0)
                              (instant "2026-08-05T10:01"))]
    (is (= :skipped (:status state)))
    (is (= [:skipped-open] (mapv :type events)))))

(deftest target-alerts-are-deduplicated
  (let [watching {:status :watching :targets-hit #{}}
        candles (entry-candles 470.0 541.0)
        first-result (swing/evaluate-setup
                      setup watching candles
                      (instant "2026-08-05T10:01"))
        second-result (swing/evaluate-setup
                       setup (:state first-result) candles
                       (instant "2026-08-05T10:02"))]
    (is (= [540.0] (mapv :price (:events first-result))))
    (is (= #{540.0} (get-in first-result [:state :targets-hit])))
    (is (empty? (:events second-result)))))

(deftest stop-and-time-exit-close-the-setup
  (testing "a live price at the stop closes the remaining position"
    (let [{:keys [state events]}
          (swing/evaluate-setup
           setup {:status :watching :targets-hit #{}}
           (entry-candles 470.0 417.0)
           (instant "2026-08-05T10:01"))]
      (is (= :closed (:status state)))
      (is (= [:stop] (mapv :type events)))))

  (testing "the configured date closes a position even without fresh data"
    (let [{:keys [state events]}
          (swing/evaluate-setup
           setup {:status :watching :targets-hit #{}} []
           (instant "2026-08-12T15:55"))]
      (is (= :closed (:status state)))
      (is (= [:time-exit] (mapv :type events))))))

(deftest rejects-unsafe-public-configuration
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"symbol contains unsupported characters"
       (swing/plan!
        {:swing-alerts
         {:setups [(assoc setup :symbol "../../private-file")]}}))))
