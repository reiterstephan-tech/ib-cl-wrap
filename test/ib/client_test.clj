(ns ib.client-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.client :as client]
            [ib.events :as events]))

(definterface IReqPosClient
  (reqPositions [])
  (eDisconnect [])
  (reqAccountSummary [reqId group tags])
  (cancelAccountSummary [reqId])
  (reqAccountUpdates [subscribe account]))

(definterface IStartApiClient
  (startAPI []))

(definterface ILoopClient
  (isConnected []))

(definterface ISignal
  (waitForSignal []))

(definterface IReader
  (processMsgs []))

(deftest private-helper-functions-test
  (testing "default-for-return-type handles primitive return types"
    (let [f #'ib.client/default-for-return-type]
      (is (= false (f Boolean/TYPE)))
      (is (= (char 0) (f Character/TYPE)))
      (is (= 0 (f Integer/TYPE)))
      (is (nil? (f String)))))

  (testing "handler-error-event supports multiple callback signatures"
    (let [f #'ib.client/handler-error-event
          throwable-evt (f [(RuntimeException. "x")])
          triple-evt (f [11 22 " msg "])
          quad-evt (f [1 2 "boom" :raw])
          unknown-evt (f [])]
      (is (= :ib/error (:type throwable-evt)))
      (is (= "x" (:message throwable-evt)))
      (is (= 11 (:id triple-evt)))
      (is (= 22 (:code triple-evt)))
      (is (= :raw (:raw quad-evt)))
      (is (= "Unknown IB error callback payload" (:message unknown-evt)))))

  (testing "invoke-method and new-instance wrappers work"
    (let [new-instance #'ib.client/new-instance
          invoke-method #'ib.client/invoke-method
          sb (new-instance StringBuilder ["abc"])]
      (is (= "abc" (str sb)))
      (is (= 3 (invoke-method sb "length")))))

  (testing "maybe-start-api! swallows missing method and calls existing method"
    (let [maybe-start #'ib.client/maybe-start-api!
          called? (atom false)
          c (reify IStartApiClient
              (startAPI [_] (reset! called? true)))]
      (is (nil? (maybe-start (Object.))))
      (maybe-start c)
      (is (true? @called?)))))

(deftest start-reader-loop-test
  (testing "reader loop exits cleanly when client is disconnected"
    (let [publish-events (atom [])
          publish! (fn [e] (swap! publish-events conj e))
          c (reify ILoopClient
              (isConnected [_] false))
          s (reify ISignal
              (waitForSignal [_] nil))
          r (reify IReader
              (processMsgs [_] nil))
          t (#'ib.client/start-reader-loop! c s r publish!)]
      (.join ^Thread t 200)
      (is (not (.isAlive ^Thread t)))
      (is (empty? @publish-events)))))

(deftest connect-missing-jar-test
  (testing "connect! throws clear error when IB classes are unavailable"
    (with-redefs [ib.client/resolve-class (constantly nil)]
      (let [data (-> (try
                       (client/connect! {:host "127.0.0.1" :port 7497 :client-id 7})
                       (catch clojure.lang.ExceptionInfo e e))
                     ex-data)]
        (is (map? data))
        (is (vector? (:missing-classes data)))
        (is (seq (:missing-classes data)))))))

(deftest req-positions-test
  (testing "req-positions! invokes reqPositions on client"
    (let [called? (atom false)
          c (reify IReqPosClient
              (reqPositions [_] (reset! called? true))
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (true? (client/req-positions! {:client c})))
      (is (true? @called?))))

  (testing "req-positions! fails with missing client"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-positions! {})))))

(deftest subscription-and-drop-count-test
  (let [{:keys [events-mult]} (events/create-event-bus {:buffer-size 8
                                                        :overflow-strategy :sliding})
        sub-ch (client/subscribe-events! {:events-mult events-mult})]
    (testing "subscribe-events! returns channel and unsubscribe returns same channel"
      (is sub-ch)
      (is (= sub-ch (client/unsubscribe-events! {:events-mult events-mult} sub-ch))))

    (testing "subscribe-events! fails with missing events-mult"
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/subscribe-events! {}))))

    (testing "dropped-event-count returns atom value and default zero"
      (is (= 3 (client/dropped-event-count {:dropped-events (atom 3)})))
      (is (= 0 (client/dropped-event-count {}))))))

(deftest disconnect-test
  (testing "disconnect! emits disconnected marker and closes event channel"
    (let [called? (atom false)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] (reset! called? true))
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))
          bus (events/create-event-bus {:buffer-size 8 :overflow-strategy :sliding})
          conn {:client c
                :events (:events bus)
                :publish! (:publish! bus)}
          result (client/disconnect! conn)]
      (is (true? (:disconnected? result)))
      (is (true? @called?))
      (is (false? (async/offer! (:events bus) {:type :x}))))))

(deftest account-summary-req-cancel-test
  (testing "req-account-summary! sends expected params and returns req-id"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ req-id group tags]
                (reset! seen [req-id group tags]))
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (= 77 (client/req-account-summary! {:client c}
                                             {:req-id 77
                                              :group "All"
                                              :tags ["NetLiquidation" "BuyingPower"]})))
      (is (= [77 "All" "NetLiquidation,BuyingPower"] @seen))))

  (testing "cancel-account-summary! calls cancel"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ req-id]
                (reset! seen req-id))
              (reqAccountUpdates [_ _ _] nil))]
      (is (true? (client/cancel-account-summary! {:client c} 31)))
      (is (= 31 @seen))))

  (testing "account summary API validates req-id"
    (let [c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-account-summary! {:client c} {:group "All"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/cancel-account-summary! {:client c} nil))))))

(deftest account-updates-req-cancel-test
  (testing "req-account-updates! subscribes account stream"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ subscribe? account]
                (reset! seen [subscribe? account])))]
      (is (true? (client/req-account-updates! {:client c} {:account "DU123"})))
      (is (= [true "DU123"] @seen))))

  (testing "cancel-account-updates! unsubscribes"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ subscribe? account]
                (reset! seen [subscribe? account])))]
      (is (true? (client/cancel-account-updates! {:client c} "DU123")))
      (is (= [false "DU123"] @seen))))

  (testing "account-updates API validates account"
    (let [c (reify IReqPosClient
              (reqPositions [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-account-updates! {:client c} {}))))))
