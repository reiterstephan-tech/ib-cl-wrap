(ns ib.spec-generative-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [ib.account :as account]
            [ib.events :as events]
            [ib.open-orders :as open-orders]
            [ib.positions :as positions]))

(defn- exercise-values [spec-key n]
  (map second (s/exercise spec-key n)))

(deftest generated-contracts-and-order-maps-conform
  (testing "generated contract maps and derived events conform to specs"
    (doseq [contract (exercise-values :ib.event/contract 60)]
      (is (s/valid? :ib.event/contract (events/contract->map contract)))
      (is (s/valid? :ib/event
                    (events/position->event {:account "DUX"
                                             :contract contract
                                             :position 1.0
                                             :avg-cost 2.0})))))

  (testing "generated req-id values can build valid account-summary events"
    (doseq [rid (exercise-values :ib/req-id 40)]
      (is (s/valid? :ib/event
                    (events/account-summary->event {:req-id rid
                                                    :account "DU123"
                                                    :tag "NetLiquidation"
                                                    :value "100"
                                                    :currency "USD"}))))))

(deftest generated-position-streams-produce-valid-results
  (doseq [n (range 0 15)]
    (let [events-ch (async/chan 64)
          out (positions/positions-snapshot-from-events! events-ch {:timeout-ms 200})]
      (dotimes [i n]
        (async/>!! events-ch (events/position->event {:account "DU1"
                                                      :contract {:conId i
                                                                 :symbol "AAPL"
                                                                 :secType "STK"
                                                                 :currency "USD"
                                                                 :exchange "SMART"}
                                                      :position i
                                                      :avg-cost 100.0})))
      (async/>!! events-ch (events/position-end->event))
      (let [result (async/<!! out)]
        (is (s/valid? :ib.result/positions-snapshot result))))))

(deftest generated-account-summary-streams-produce-valid-results
  (doseq [n (range 1 12)]
    (let [rid n
          events-ch (async/chan 64)
          out (account/account-summary-snapshot-from-events! events-ch {:req-id rid
                                                                        :timeout-ms 200})]
      (dotimes [i n]
        (async/>!! events-ch
                   (events/account-summary->event {:req-id rid
                                                   :account "DU123"
                                                   :tag (str "Tag" i)
                                                   :value (str i)
                                                   :currency "USD"})))
      (async/>!! events-ch (events/account-summary-end->event {:req-id rid}))
      (let [result (async/<!! out)]
        (is (s/valid? :ib.result/account-summary result))))))

(deftest generated-open-order-streams-produce-valid-results
  (doseq [n (range 1 12)]
    (let [events-ch (async/chan 64)
          out (open-orders/open-orders-snapshot-from-events! events-ch {:timeout-ms 200})]
      (dotimes [i n]
        (async/>!! events-ch
                   (events/open-order->event {:order-id i
                                              :contract {:conId i
                                                         :symbol "AAPL"
                                                         :secType "STK"
                                                         :currency "USD"
                                                         :exchange "SMART"}
                                              :order {:action "BUY"
                                                      :orderType "LMT"
                                                      :totalQuantity 1.0
                                                      :lmtPrice 150.0
                                                      :auxPrice 0.0
                                                      :tif "DAY"
                                                      :transmit true
                                                      :parentId 0}
                                              :order-state {:status "Submitted"
                                                            :commission 0.0
                                                            :warningText ""}})))
      (async/>!! events-ch (events/open-order-end->event))
      (let [result (async/<!! out)]
        (is (s/valid? :ib.result/open-orders-snapshot result))))))
