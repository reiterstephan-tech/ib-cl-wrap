(ns ib.events-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.events :as events]))

(deftest contract-map-normalization-test
  (testing "normalizes maps with canonical keys"
    (is (= {:conId 1234
            :symbol "AAPL"
            :secType "STK"
            :currency "USD"
            :exchange "SMART"}
           (events/contract->map {:conId 1234
                                  :symbol "AAPL"
                                  :secType "STK"
                                  :currency "USD"
                                  :exchange "SMART"}))))

  (testing "normalizes maps with alternative keys"
    (is (= {:conId 5678
            :symbol "MSFT"
            :secType "STK"
            :currency "USD"
            :exchange "NASDAQ"}
           (events/contract->map {"conid" "5678"
                                  "symbol" "MSFT"
                                  "secType" "STK"
                                  "currency" "USD"
                                  "exchange" "NASDAQ"}))))

  (testing "handles nil contract"
    (is (= {:conId nil
            :symbol nil
            :secType nil
            :currency nil
            :exchange nil}
           (events/contract->map nil)))))

(deftest event-bus-overflow-test
  (testing "accepts explicit overflow strategies"
    (let [sliding (events/create-event-bus {:buffer-size 2 :overflow-strategy :sliding})
          dropping (events/create-event-bus {:buffer-size 2 :overflow-strategy :dropping})]
      (is (= :sliding (:overflow-strategy sliding)))
      (is (= :dropping (:overflow-strategy dropping)))
      (is (true? ((:publish! sliding) {:type :x})))
      (is (true? ((:publish! dropping) {:type :x}))))
    true)

  (testing "invalid overflow strategy falls back to sliding"
    (let [{:keys [overflow-strategy]} (events/create-event-bus {:buffer-size 2
                                                                :overflow-strategy :invalid})]
      (is (= :sliding overflow-strategy)))))

(deftest event-bus-subscribe-and-closed-publish-test
  (let [{:keys [events events-mult dropped-events publish!]} (events/create-event-bus {:buffer-size 2
                                                                                        :overflow-strategy :sliding})
        sub (events/subscribe! events-mult {:buffer-size 8})]
    (testing "subscribers receive published events"
      (publish! {:type :ib/ping})
      (is (= :ib/ping (:type (async/<!! sub)))))

    (testing "publish! increments dropped counter when channel is closed"
      (async/close! events)
      (is (false? (publish! {:type :ib/after-close})))
      (is (= 1 @dropped-events)))

    (events/unsubscribe! events-mult sub)
    (async/close! sub)))

(deftest event-normalization-test
  (testing "position->event normalizes numbers and contract"
    (let [evt (events/position->event {:account "DU123"
                                       :contract {"conid" "101" "symbol" "AAPL" "secType" "STK" "currency" "USD" "exchange" "SMART"}
                                       :position "12.5"
                                       :avg-cost 10})]
      (is (= :ib/position (:type evt)))
      (is (= "DU123" (:account evt)))
      (is (= 101 (get-in evt [:contract :conId])))
      (is (= 12.5 (:position evt)))
      (is (= 10.0 (:avg-cost evt)))))

  (testing "error->event trims message"
    (let [evt (events/error->event {:id 1 :code 2 :message "  boom  " :raw :x})]
      (is (= :ib/error (:type evt)))
      (is (= "boom" (:message evt)))
      (is (= 1 (:id evt)))
      (is (= 2 (:code evt)))))

  (testing "account-summary->event normalizes payload"
    (let [evt (events/account-summary->event {:req-id "5"
                                              :account "DU123"
                                              :tag "NetLiquidation"
                                              :value "12345.67"
                                              :currency "USD"})]
      (is (= :ib/account-summary (:type evt)))
      (is (= 5 (:req-id evt)))
      (is (= "DU123" (:account evt)))
      (is (= "NetLiquidation" (:tag evt)))
      (is (= "12345.67" (:value evt)))
      (is (= "USD" (:currency evt))))))
