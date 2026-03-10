(ns ib.client-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.client :as client]
            [ib.events :as events]))

(definterface IReqPosClient
  (reqPositions [])
  (reqOpenOrders [])
  (reqAllOpenOrders [])
  (eDisconnect [])
  (reqAccountSummary [reqId group tags])
  (cancelAccountSummary [reqId])
  (reqAccountUpdates [subscribe account]))

(definterface IStartApiClient
  (startAPI []))

(definterface IOrderClient
  (reqIds [numIds])
  (placeOrder [orderId contract order])
  (cancelOrder [orderId manualCancelTime]))

(definterface IMktDataClient
  (isConnected [])
  (reqMktDataType [marketDataType])
  (reqMktData [tickerId contract genericTickList snapshot regulatorySnapshot mktDataOptions])
  (cancelMktData [tickerId]))

(definterface IContractDetailsClient
  (reqContractDetails [reqId contract]))

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
          t (#'ib.client/start-reader-loop! c s r publish! nil)]
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
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (true? (client/req-positions! {:client (atom c)})))
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
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] (reset! called? true))
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))
          bus (events/create-event-bus {:buffer-size 8 :overflow-strategy :sliding})
          conn {:client (atom c)
                :reader-thread (atom nil)
                :manual-disconnect? (atom false)
                :events (:events bus)
                :publish! (:publish! bus)}
          result (client/disconnect! conn)]
      (is (true? (:disconnected? result)))
      (is (true? @called?))
      (is (false? (async/offer! (:events bus) {:type :x}))))))

(deftest account-summary-req-cancel-test
  (testing "req-account-summary! sends expected params and returns req-id"
    (let [seen (atom nil)
          registry (atom {})
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ req-id group tags]
                (reset! seen [req-id group tags]))
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (= 77 (client/req-account-summary! {:client (atom c) :request-registry registry}
                                             {:req-id 77
                                              :group "All"
                                              :tags ["NetLiquidation" "BuyingPower"]})))
      (is (= [77 "All" "NetLiquidation,BuyingPower"] @seen))))

  (testing "cancel-account-summary! calls cancel"
    (let [seen (atom nil)
          registry (atom {31 {:type :account-summary}})
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ req-id]
                (reset! seen req-id))
              (reqAccountUpdates [_ _ _] nil))]
      (is (true? (client/cancel-account-summary! {:client (atom c) :request-registry registry} 31)))
      (is (= 31 @seen))
      (is (nil? (get @registry 31)))))

  (testing "account summary API validates req-id"
    (let [c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-account-summary! {:client (atom c) :request-registry (atom {})} {:group "All"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/cancel-account-summary! {:client (atom c) :request-registry (atom {})} nil)))))

(deftest request-correlation-helpers-test
  (let [registry (atom {})
        conn {:request-registry registry}
        enrich #'ib.client/enrich-error-event]
    (client/register-request! conn 99 {:type :account-summary :group "All"})
    (is (= :account-summary (:type (client/request-context conn 99))))
    (let [evt (enrich registry {:type :ib/error :id 99 :code 2104 :message "x"})]
      (is (= 99 (:request-id evt)))
      (is (= :account-summary (get-in evt [:request :type])))
      (is (true? (:retryable? evt))))
    (client/unregister-request! conn 99)
    (is (nil? (client/request-context conn 99))))))

(deftest account-updates-req-cancel-test
  (testing "req-account-updates! subscribes account stream"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ subscribe? account]
                (reset! seen [subscribe? account])))]
      (is (true? (client/req-account-updates! {:client (atom c)} {:account "DU123"})))
      (is (= [true "DU123"] @seen))))

  (testing "cancel-account-updates! unsubscribes"
    (let [seen (atom nil)
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ subscribe? account]
                (reset! seen [subscribe? account])))]
      (is (true? (client/cancel-account-updates! {:client (atom c)} "DU123")))
      (is (= [false "DU123"] @seen))))

  (testing "account-updates API validates account"
    (let [c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] nil)
              (reqAllOpenOrders [_] nil)
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-account-updates! {:client (atom c)} {}))))))

(deftest open-orders-request-api-test
  (testing "req-open-orders! and req-all-open-orders! invoke IB client methods"
    (let [calls (atom [])
          c (reify IReqPosClient
              (reqPositions [_] nil)
              (reqOpenOrders [_] (swap! calls conj :open))
              (reqAllOpenOrders [_] (swap! calls conj :all))
              (eDisconnect [_] nil)
              (reqAccountSummary [_ _ _ _] nil)
              (cancelAccountSummary [_ _] nil)
              (reqAccountUpdates [_ _ _] nil))]
      (is (true? (client/req-open-orders! {:client (atom c)})))
      (is (true? (client/req-all-open-orders! {:client (atom c)})))
      (is (= [:open :all] @calls))))

  (testing "open orders request API fails with missing client"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-open-orders! {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-all-open-orders! {})))))

(deftest reconnect-on-disconnect-callback-test
  (testing "on-disconnect callback fires when reader thread exits"
    (let [callback-fired? (atom false)
          on-disconnect (fn [] (reset! callback-fired? true))
          publish! (fn [_] nil)
          c (reify ILoopClient
              (isConnected [_] false))
          s (reify ISignal
              (waitForSignal [_] nil))
          r (reify IReader
              (processMsgs [_] nil))
          t (#'ib.client/start-reader-loop! c s r publish! on-disconnect)]
      (.join ^Thread t 200)
      (is (not (.isAlive ^Thread t)))
      (is (true? @callback-fired?)))))

(deftest reconnect-loop-aborts-on-manual-disconnect-test
  (testing "start-reconnect-loop! aborts immediately when manual-disconnect? is true"
    (let [published (atom [])
          publish! (fn [e] (swap! published conj e))
          manual-disconnect? (atom true)
          reconnecting? (atom true)
          conn {:publish! publish!
                :manual-disconnect? manual-disconnect?
                :reconnecting? reconnecting?
                :host "127.0.0.1"
                :port 7497
                :client-id 0
                :reconnect-opts {:max-attempts 3
                                 :initial-delay-ms 1
                                 :max-delay-ms 10}}]
      (#'ib.client/start-reconnect-loop! conn nil)
      (Thread/sleep 50)
      (is (empty? @published))
      (is (false? @reconnecting?)))))

(deftest reconnect-loop-max-attempts-test
  (testing "start-reconnect-loop! emits reconnect-failed after max attempts exhausted"
    (with-redefs [ib.client/attempt-reconnect! (fn [_ _] nil)]
      (let [published (atom [])
            publish! (fn [e] (swap! published conj e))
            manual-disconnect? (atom false)
            reconnecting? (atom true)
            conn {:publish! publish!
                  :manual-disconnect? manual-disconnect?
                  :reconnecting? reconnecting?
                  :host "127.0.0.1"
                  :port 7497
                  :client-id 0
                  :reconnect-opts {:max-attempts 2
                                   :initial-delay-ms 1
                                   :max-delay-ms 10}}]
        (#'ib.client/start-reconnect-loop! conn nil)
        (Thread/sleep 300)
        (let [types (mapv :type @published)]
          (is (= 2 (count (filter #(= % :ib/reconnecting) types))))
          (is (= :ib/reconnect-failed (last types)))
          (is (false? @reconnecting?)))))))

(deftest reconnect-loop-succeeds-on-second-attempt-test
  (testing "start-reconnect-loop! succeeds on second attempt and updates conn atoms"
    (let [attempt-count (atom 0)
          mock-client (Object.)
          mock-reader (Object.)
          mock-thread (Thread. (fn []))
          published (atom [])
          publish! (fn [e] (swap! published conj e))
          manual-disconnect? (atom false)
          reconnecting? (atom true)
          client-atom (atom nil)
          reader-atom (atom nil)
          reader-thread-atom (atom nil)
          conn {:publish! publish!
                :manual-disconnect? manual-disconnect?
                :reconnecting? reconnecting?
                :client client-atom
                :reader reader-atom
                :reader-thread reader-thread-atom
                :host "127.0.0.1"
                :port 7497
                :client-id 0
                :reconnect-opts {:max-attempts 3
                                 :initial-delay-ms 1
                                 :max-delay-ms 10}}]
      (with-redefs [ib.client/attempt-reconnect!
                    (fn [_ _]
                      (swap! attempt-count inc)
                      (if (= @attempt-count 1)
                        nil
                        {:client mock-client
                         :reader mock-reader
                         :reader-thread mock-thread}))]
        (#'ib.client/start-reconnect-loop! conn nil)
        (Thread/sleep 300)
        (let [types (mapv :type @published)]
          (is (= 2 (count (filter #(= % :ib/reconnecting) types))))
          (is (= :ib/reconnected (last types)))
          (is (false? @reconnecting?))
          (is (identical? mock-client @client-atom))
          (is (identical? mock-reader @reader-atom))
          (is (identical? mock-thread @reader-thread-atom)))))))

(deftest req-ids-test
  (testing "req-ids! calls reqIds with 1 and returns true"
    (let [seen (atom nil)
          c (reify IOrderClient
              (reqIds [_ num-ids] (reset! seen num-ids))
              (placeOrder [_ _ _ _] nil)
              (cancelOrder [_ _ _] nil))]
      (is (true? (client/req-ids! {:client (atom c)})))
      (is (= 1 @seen))))

  (testing "req-ids! throws when client is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-ids! {})))))

(deftest next-order-id-counter-test
  (testing "counter returns current value and increments"
    (let [counter (atom 100)
          conn {:next-order-id counter}]
      (is (= 100 (client/next-order-id! conn)))
      (is (= 101 @counter))
      (is (= 101 (client/next-order-id! conn)))
      (is (= 102 @counter))))

  (testing "throws when counter is unseeded (nil value)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/next-order-id! {:next-order-id (atom nil)}))))

  (testing "throws when :next-order-id key is absent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/next-order-id! {})))))

(deftest place-order-test
  (let [mock-contract (Object.)
        mock-order    (Object.)]
    (with-redefs [ib.client/map->contract (constantly mock-contract)
                  ib.client/map->order    (constantly mock-order)]

      (testing "place-order! with explicit :order-id invokes placeOrder and returns order-id"
        (let [seen (atom nil)
              c (reify IOrderClient
                  (reqIds [_ _] nil)
                  (placeOrder [_ oid contract order]
                    (reset! seen [oid contract order]))
                  (cancelOrder [_ _ _] nil))]
          (is (= 42 (client/place-order! {:client (atom c)
                                          :next-order-id (atom 99)}
                                         {:order-id 42
                                          :contract {:symbol "AAPL"}
                                          :order {:action "BUY" :total-quantity 100}})))
          (is (= [42 mock-contract mock-order] @seen))))

      (testing "place-order! auto-allocates order-id from counter when :order-id absent"
        (let [seen (atom nil)
              counter (atom 7)
              c (reify IOrderClient
                  (reqIds [_ _] nil)
                  (placeOrder [_ oid contract order]
                    (reset! seen [oid contract order]))
                  (cancelOrder [_ _ _] nil))]
          (is (= 7 (client/place-order! {:client (atom c)
                                         :next-order-id counter}
                                        {:contract {:symbol "AAPL"}
                                         :order {:action "BUY" :total-quantity 100}})))
          (is (= 7 (first @seen)))
          (is (= 8 @counter))))

      (testing "place-order! throws when client is missing"
        (is (thrown? clojure.lang.ExceptionInfo
                     (client/place-order! {} {:order-id 1
                                              :contract mock-contract
                                              :order mock-order})))))))

(deftest cancel-order-test
  (testing "cancel-order! calls cancelOrder with correct order-id and returns true"
    (let [seen (atom nil)
          c (reify IOrderClient
              (reqIds [_ _] nil)
              (placeOrder [_ _ _ _] nil)
              (cancelOrder [_ oid _] (reset! seen oid)))]
      (is (true? (client/cancel-order! {:client (atom c)} 55)))
      (is (= 55 @seen))))

  (testing "cancel-order! throws for non-integer order-id"
    (let [c (reify IOrderClient
              (reqIds [_ _] nil)
              (placeOrder [_ _ _ _] nil)
              (cancelOrder [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/cancel-order! {:client (atom c)} "not-an-int")))))

  (testing "cancel-order! throws when client is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/cancel-order! {} 1)))))

(deftest tick-price-event-test
  (testing "tick-price->event maps known field integers to keywords"
    (let [ev (events/tick-price->event {:req-id 5 :field 1 :price 179.5})]
      (is (= :ib/tick-price (:type ev)))
      (is (= 5 (:req-id ev)))
      (is (= 1 (:field ev)))
      (is (= :bid (:field-key ev)))
      (is (= 179.5 (:price ev)))))

  (testing "tick-price->event sets field-key nil for unknown field"
    (let [ev (events/tick-price->event {:req-id 5 :field 99 :price 1.0})]
      (is (nil? (:field-key ev)))))

  (testing "tick-snapshot-end->event sets req-id"
    (let [ev (events/tick-snapshot-end->event {:req-id 42})]
      (is (= :ib/tick-snapshot-end (:type ev)))
      (is (= 42 (:req-id ev))))))

(deftest connected?-test
  (testing "connected? returns true when isConnected returns true"
    (let [c (reify IMktDataClient
              (isConnected [_] true)
              (reqMktDataType [_ _] nil)
              (reqMktData [_ _ _ _ _ _ _] nil)
              (cancelMktData [_ _] nil))]
      (is (true? (client/connected? {:client (atom c)})))))

  (testing "connected? returns false when client atom is nil"
    (is (false? (client/connected? {:client (atom nil)}))))

  (testing "connected? returns false when :client key is absent"
    (is (false? (client/connected? {})))))

(deftest req-market-data-type-test
  (testing "req-market-data-type! calls reqMktDataType with correct arg"
    (let [seen (atom nil)
          c (reify IMktDataClient
              (isConnected [_] true)
              (reqMktDataType [_ t] (reset! seen t))
              (reqMktData [_ _ _ _ _ _ _] nil)
              (cancelMktData [_ _] nil))]
      (is (true? (client/req-market-data-type! {:client (atom c)} 4)))
      (is (= 4 @seen))))

  (testing "req-market-data-type! throws when client is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-market-data-type! {} 4)))))

(deftest req-mkt-data-test
  (with-redefs [ib.client/map->contract (constantly (Object.))]
    (testing "req-mkt-data! calls reqMktData and returns true"
      (let [seen (atom nil)
            c (reify IMktDataClient
                (isConnected [_] true)
                (reqMktDataType [_ _] nil)
                (reqMktData [_ ticker-id _ _ snapshot _ _]
                  (reset! seen {:ticker-id ticker-id :snapshot snapshot}))
                (cancelMktData [_ _] nil))]
        (is (true? (client/req-mkt-data! {:client (atom c)}
                                         {:req-id 99 :symbol "AAPL" :snapshot true})))
        (is (= 99 (:ticker-id @seen)))
        (is (true? (:snapshot @seen)))))

    (testing "req-mkt-data! throws for missing req-id"
      (let [c (reify IMktDataClient
                (isConnected [_] true)
                (reqMktDataType [_ _] nil)
                (reqMktData [_ _ _ _ _ _ _] nil)
                (cancelMktData [_ _] nil))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (client/req-mkt-data! {:client (atom c)} {:symbol "AAPL"})))))

    (testing "req-mkt-data! throws when client is missing"
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-mkt-data! {} {:req-id 1 :symbol "AAPL"}))))))

(deftest cancel-mkt-data-test
  (testing "cancel-mkt-data! calls cancelMktData with correct req-id"
    (let [seen (atom nil)
          c (reify IMktDataClient
              (isConnected [_] true)
              (reqMktDataType [_ _] nil)
              (reqMktData [_ _ _ _ _ _ _] nil)
              (cancelMktData [_ id] (reset! seen id)))]
      (is (true? (client/cancel-mkt-data! {:client (atom c)} 77)))
      (is (= 77 @seen))))

  (testing "cancel-mkt-data! throws for non-integer req-id"
    (let [c (reify IMktDataClient
              (isConnected [_] true)
              (reqMktDataType [_ _] nil)
              (reqMktData [_ _ _ _ _ _ _] nil)
              (cancelMktData [_ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/cancel-mkt-data! {:client (atom c)} "bad")))))

  (testing "cancel-mkt-data! throws when client is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/cancel-mkt-data! {} 1)))))

(deftest req-contract-details-test
  (testing "req-contract-details! calls reqContractDetails with correct req-id"
    (let [seen (atom nil)
          c (reify IContractDetailsClient
              (reqContractDetails [_ req-id _contract]
                (reset! seen req-id)))]
      (is (true? (client/req-contract-details! {:client (atom c)}
                                               {:req-id 55 :symbol "AAPL"})))
      (is (= 55 @seen))))

  (testing "req-contract-details! throws for missing req-id"
    (let [c (reify IContractDetailsClient
              (reqContractDetails [_ _ _] nil))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (client/req-contract-details! {:client (atom c)} {:symbol "AAPL"})))))

  (testing "req-contract-details! throws when client is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/req-contract-details! {} {:req-id 1 :symbol "AAPL"})))))
