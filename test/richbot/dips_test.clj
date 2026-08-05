(ns richbot.dips-test
  (:require [clojure.test :refer [deftest is testing]]
            [richbot.alert :as alert]
            [richbot.dips :as dips]))

(defn- pending [opened amount]
  {:opened opened
   :trade {:amount amount
           :executed? false}})

(deftest pending-reminders-are-not-daily
  (let [messages (atom [])
        rows [{:symbol "MC.PA" :score 31.9}]
        state {:trades {"MC.PA" (pending "2026-08-03" 150.0)}}]
    (with-redefs [alert/send! #(swap! messages conj %)]
      (testing "the first reminder waits three days"
        (let [result (#'dips/pending-execution-alert!
                      "2026-08-05" rows state)]
          (is (empty? @messages))
          (is (nil? (get-in result
                            [:trades "MC.PA" :last-pending-reminder])))))
      (testing "each pending idea is reminded only once"
        (let [reminded (#'dips/pending-execution-alert!
                        "2026-08-06" rows state)
              next-day (#'dips/pending-execution-alert!
                        "2026-08-07" rows reminded)]
          (is (= 1 (count @messages)))
          (is (= "2026-08-06"
                 (get-in reminded
                         [:trades "MC.PA" :last-pending-reminder])))
          (is (= reminded next-day)))))))

(deftest ineligible-pending-ideas-release-budget
  (let [messages (atom [])
        state {:budget {:month "2026-08" :spent 200.0}
               :trades {"MC.PA" (pending "2026-08-03" 150.0)
                        "META" (pending "2026-08-03" 50.0)}}
        rows [{:symbol "MC.PA" :amount 150.0 :score 31.9}
              {:symbol "META" :amount 0.0 :score 7.6}]]
    (with-redefs [alert/send! #(swap! messages conj %)]
      (let [result (#'dips/drop-ineligible-pending!
                    "2026-08-05" rows state)]
        (is (contains? (:trades result) "MC.PA"))
        (is (not (contains? (:trades result) "META")))
        (is (= 150.0 (get-in result [:budget :spent])))
        (is (= 1 (count @messages)))))))

(deftest missing-market-data-does-not-cancel-pending-idea
  (let [state {:budget {:month "2026-08" :spent 50.0}
               :trades {"META" (pending "2026-08-03" 50.0)}}]
    (with-redefs [alert/send! (fn [_])]
      (is (= state
             (#'dips/drop-ineligible-pending!
              "2026-08-05" [] state))))))

(deftest materially-better-idea-can-replace-weaker-pending-budget
  (let [cfg {:opportunity-budget 300.0}
        state {:budget {:month "2026-08" :spent 200.0}
               :trades {"MC.PA" (pending "2026-08-03" 150.0)
                        "META" (pending "2026-08-03" 50.0)}}
        rows [{:symbol "MC.PA" :score 31.9}
              {:symbol "META" :score 7.6}]
        fresh [{:symbol "SPGI" :score 30.0 :amount 150.0}]
        plan (#'dips/replacement-plan
              cfg "2026-08-05" state rows fresh)]
    (is (= "SPGI" (get-in plan [:candidate :symbol])))
    (is (= ["META"] (mapv :symbol (:released plan))))))

(deftest ranking-is-continuous-around-buy-threshold
  (let [row {:discount 0.049
             :off-high 0.20
             :ret-1y 0.10
             :quality 8
             :valuation 7}
        below (#'dips/score row)
        above (#'dips/score (assoc row :discount 0.05))]
    (is (< (Math/abs (- above below)) 1.0))))
