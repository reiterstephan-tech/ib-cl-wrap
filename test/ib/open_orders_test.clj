(ns ib.open-orders-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.open-orders :as oo]))

(deftest open-orders-snapshot-collector-success-test
  (testing "collects open-order events until open-order-end"
    (let [events (async/chan 10)
          out (oo/open-orders-snapshot-from-events! events {:timeout-ms 500})]
      (async/>!! events {:type :ib/order-status})
      (async/>!! events {:type :ib/open-order :order-id 1})
      (async/>!! events {:type :ib/open-order :order-id 2})
      (async/>!! events {:type :ib/open-order-end})
      (let [result (async/<!! out)]
        (is (true? (:ok result)))
        (is (= 2 (count (:orders result))))
        (is (= [1 2] (mapv :order-id (:orders result))))))))

(deftest open-orders-snapshot-collector-timeout-test
  (testing "returns timeout result instead of hanging"
    (let [events (async/chan 1)
          out (oo/open-orders-snapshot-from-events! events {:timeout-ms 25})
          result (async/<!! out)]
      (is (false? (:ok result)))
      (is (= :timeout (:error result))))))

(deftest open-orders-snapshot-guard-test
  (testing "fails fast when another open-order snapshot is already in flight"
    (let [sub-ch (async/chan 1)
          in-flight (atom true)]
      (with-redefs [ib.client/subscribe-events! (fn [_ _] sub-ch)
                    ib.client/req-open-orders! (fn [_] true)
                    ib.client/req-all-open-orders! (fn [_] true)
                    ib.client/unsubscribe-events! (fn [_ _] true)]
        (let [out (oo/open-orders-snapshot! {:open-orders-snapshot-in-flight in-flight}
                                            {:mode :open :timeout-ms 100})
              result (async/<!! out)]
          (is (false? (:ok result)))
          (is (= :snapshot-in-flight (:error result)))
          (is (true? @in-flight)))))))

(deftest open-orders-snapshot-request-failure-releases-guard-test
  (testing "releases guard when request invocation fails"
    (let [in-flight (atom false)
          sub-ch (async/chan 1)]
      (with-redefs [ib.client/subscribe-events! (fn [_ _] sub-ch)
                    ib.client/req-open-orders! (fn [_] (throw (ex-info "boom" {})))
                    ib.client/req-all-open-orders! (fn [_] true)
                    ib.client/unsubscribe-events! (fn [_ _] true)]
        (let [out (oo/open-orders-snapshot! {:open-orders-snapshot-in-flight in-flight}
                                            {:mode :open :timeout-ms 100})
              result (async/<!! out)]
          (is (false? (:ok result)))
          (is (= :request-failed (:error result)))
          (is (false? @in-flight)))))))
