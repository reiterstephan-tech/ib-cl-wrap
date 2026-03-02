(ns ib.account-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.account :as account]))

(deftest account-summary-collector-success-test
  (testing "collector aggregates matching req-id until account-summary-end"
    (let [events (async/chan 10)
          out (account/account-summary-snapshot-from-events! events {:req-id 101
                                                                     :timeout-ms 500})]
      (async/>!! events {:type :ib/account-summary
                         :req-id 999
                         :account "DU-OTHER"
                         :tag "NetLiquidation"
                         :value "1"
                         :currency "USD"})
      (async/>!! events {:type :ib/account-summary
                         :req-id 101
                         :account "DU111"
                         :tag "NetLiquidation"
                         :value "1000.0"
                         :currency "USD"})
      (async/>!! events {:type :ib/account-summary
                         :req-id 101
                         :account "DU111"
                         :tag "AvailableFunds"
                         :value "300.0"
                         :currency "USD"})
      (async/>!! events {:type :ib/account-summary-end
                         :req-id 101})
      (let [result (async/<!! out)]
        (is (true? (:ok result)))
        (is (= 101 (:req-id result)))
        (is (= "1000.0" (get-in result [:values "DU111" "NetLiquidation" :value])))
        (is (= "USD" (get-in result [:values "DU111" "AvailableFunds" :currency])))))))

(deftest account-summary-collector-error-paths-test
  (testing "collector returns timeout"
    (let [events (async/chan 1)
          out (account/account-summary-snapshot-from-events! events {:req-id 22
                                                                     :timeout-ms 20})
          result (async/<!! out)]
      (is (false? (:ok result)))
      (is (= :timeout (:error result)))
      (is (= 22 (:req-id result)))))

  (testing "collector returns ib-error for matching req-id"
    (let [events (async/chan 2)
          out (account/account-summary-snapshot-from-events! events {:req-id 33
                                                                     :timeout-ms 500})]
      (async/>!! events {:type :ib/error
                         :id 33
                         :code 2104
                         :message "some ib issue"})
      (let [result (async/<!! out)]
        (is (false? (:ok result)))
        (is (= :ib-error (:error result)))
        (is (= 33 (:req-id result)))))))

(deftest account-summary-snapshot-timeout-cancels-test
  (testing "snapshot cancels account summary subscription on timeout"
    (let [sub-ch (async/chan 1)
          cancel-calls (atom [])
          unsub-calls (atom 0)]
      (with-redefs [ib.client/subscribe-events! (fn [_ _] sub-ch)
                    ib.client/req-account-summary! (fn [_ _] true)
                    ib.client/cancel-account-summary! (fn [_ req-id]
                                                        (swap! cancel-calls conj req-id)
                                                        true)
                    ib.client/unsubscribe-events! (fn [_ _]
                                                    (swap! unsub-calls inc)
                                                    true)
                    ib.account/next-req-id! (fn [] 4242)]
        (let [out (account/account-summary-snapshot! {:dummy true} {:timeout-ms 25})
              result (async/<!! out)]
          (is (false? (:ok result)))
          (is (= :timeout (:error result)))
          (is (= 4242 (:req-id result)))
          (is (= [4242] @cancel-calls))
          (is (= 1 @unsub-calls)))))))
