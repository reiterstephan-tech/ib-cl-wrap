(ns ib.client
  "Async Interactive Brokers client wrapper.

  This namespace intentionally avoids static Java imports so test runs work
  without `lib/ibapi.jar` on the classpath."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [ib.errors :as ib-errors]
            [ib.events :as events]))

(def ^:private ib-class-names
  ["com.ib.client.EWrapper"
   "com.ib.client.EReaderSignal"
   "com.ib.client.EJavaSignal"
   "com.ib.client.EClientSocket"
   "com.ib.client.EReader"])

(defn- resolve-class [class-name]
  (try
    (Class/forName class-name)
    (catch Throwable _
      nil)))

(defn- ensure-ib-classes! []
  (let [missing (remove resolve-class ib-class-names)]
    (when (seq missing)
      (throw
       (ex-info
        "Interactive Brokers Java API classes not found. Add lib/ibapi.jar to the classpath."
        {:missing-classes (vec missing)
         :hint "Place IB API jar at lib/ibapi.jar (see README)."})))))

(defn- invoke-method [target method-name & args]
  (clojure.lang.Reflector/invokeInstanceMethod target method-name (to-array args)))

(defn- new-instance [^Class clazz args]
  (clojure.lang.Reflector/invokeConstructor clazz (to-array args)))

(defn- maybe-start-api! [client]
  (try
    (invoke-method client "startAPI")
    (catch Throwable _
      nil)))

(defn- default-for-return-type [^Class return-type]
  (when (.isPrimitive return-type)
    (cond
      (= Boolean/TYPE return-type) false
      (= Character/TYPE return-type) (char 0)
      :else 0)))

(defn- handler-error-event [args]
  (let [argc (count args)]
    (cond
      (= argc 1)
      (let [x (first args)]
        (events/error->event
         {:id nil
          :code nil
          :message (if (instance? Throwable x)
                     (.getMessage ^Throwable x)
                     (str x))
          :raw x}))

      (= argc 3)
      (let [[id code message] args]
        (events/error->event
         {:id id
          :code code
          :message message
          :raw args}))

      ;; TWS API 10.19+: error(int id, long errorCode, int errorCount, String errorMsg, String advancedOrderRejectJson)
      (= argc 5)
      (let [[id code _error-count message extra] args]
        (events/error->event
         {:id id
          :code code
          :message message
          :raw extra}))

      ;; Older API: error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson)
      (>= argc 4)
      (let [[id code message extra] args]
        (events/error->event
         {:id id
          :code code
          :message message
          :raw extra}))

      :else
      (events/error->event
       {:id nil
        :code nil
        :message "Unknown IB error callback payload"
        :raw args}))))

(def default-account-summary-group
  "Default account summary group for reqAccountSummary."
  "All")

(def default-account-summary-tags
  "Pragmatic default subset of account summary tags for balances."
  ["NetLiquidation"
   "TotalCashValue"
   "AvailableFunds"
   "BuyingPower"
   "UnrealizedPnL"
   "RealizedPnL"])

(defn- normalize-account-summary-tags [tags]
  (cond
    (string? tags) tags
    (sequential? tags) (str/join "," (map str tags))
    :else (str/join "," default-account-summary-tags)))

(defn register-request!
  "Register request metadata for request-id based correlation."
  [{:keys [request-registry]} req-id request]
  (when-not request-registry
    (throw (ex-info "Connection map missing :request-registry" {})))
  (when-not (integer? req-id)
    (throw (ex-info "register-request! requires integer req-id" {:req-id req-id})))
  (swap! request-registry assoc req-id (assoc request
                                              :req-id req-id
                                              :registered-at (events/now-ms)))
  req-id)

(defn unregister-request!
  "Remove request metadata for request-id based correlation."
  [{:keys [request-registry]} req-id]
  (when request-registry
    (swap! request-registry dissoc req-id))
  true)

(defn request-context
  "Fetch registered request metadata by req-id."
  [{:keys [request-registry]} req-id]
  (when request-registry
    (get @request-registry req-id)))

(defn- enrich-error-event [request-registry error-event]
  (let [req-id (:id error-event)
        request (when (integer? req-id)
                  (get @request-registry req-id))]
    (cond-> (assoc error-event :retryable? (ib-errors/retryable-ib-error? (:code error-event)))
      request (assoc :request-id req-id
                     :request request))))

(defn- find-field
  "Walk the class hierarchy of `clazz` to find a field named `field-name`.
  Returns the Field or nil."
  [^Class clazz field-name]
  (loop [c clazz]
    (when c
      (or (try (.getDeclaredField c field-name)
               (catch NoSuchFieldException _ nil))
          (recur (.getSuperclass c))))))

(defn- set-field!
  "Set an arbitrary Java field via reflection; silently skips nil values and
  unknown fields."
  [obj field-name value]
  (when (some? value)
    (try
      (when-let [field (find-field (class obj) field-name)]
        (.setAccessible ^java.lang.reflect.Field field true)
        (.set ^java.lang.reflect.Field field obj value))
      (catch Throwable _ nil))))

(defn- set-quantity!
  "Set `Order.totalQuantity` handling the `com.ib.client.Decimal` type
  introduced in IB API 10.xx, with a plain double fallback."
  [order-obj qty]
  (when (some? qty)
    (let [decimal-class (resolve-class "com.ib.client.Decimal")]
      (if decimal-class
        (try
          (set-field! order-obj "totalQuantity"
                      (clojure.lang.Reflector/invokeStaticMethod
                       decimal-class "get" (to-array [(double qty)])))
          (catch Throwable _
            (set-field! order-obj "totalQuantity" (double qty))))
        (set-field! order-obj "totalQuantity" (double qty))))))

(defn- map->contract
  "Instantiate a `com.ib.client.Contract` from a kebab-case map.
  Recognised keys: `:symbol`, `:sec-type`, `:currency`, `:exchange`,
  `:primary-exch`, `:con-id`."
  [{:keys [symbol sec-type currency exchange primary-exch con-id]
    :or {sec-type "STK" currency "USD" exchange "SMART"}}]
  (let [c (new-instance (resolve-class "com.ib.client.Contract") [])]
    (set-field! c "symbol"      (str symbol))
    (set-field! c "secType"     (str sec-type))
    (set-field! c "currency"    (str currency))
    (set-field! c "exchange"    (str exchange))
    (set-field! c "primaryExch" (str (or primary-exch "")))
    (when con-id (set-field! c "conId" (int (long con-id))))
    c))

(defn- map->order
  "Instantiate a `com.ib.client.Order` from a kebab-case map.
  Recognised keys: `:action`, `:order-type`, `:total-quantity`, `:lmt-price`,
  `:aux-price`, `:tif`, `:transmit`, `:parent-id`."
  [{:keys [action order-type total-quantity lmt-price aux-price tif transmit parent-id]
    :or {tif "DAY" transmit true}}]
  (let [o (new-instance (resolve-class "com.ib.client.Order") [])]
    (set-field! o "action"    (some-> action str str/upper-case))
    (set-field! o "orderType" (some-> order-type str str/upper-case))
    (set-quantity! o total-quantity)
    (when lmt-price  (set-field! o "lmtPrice"  (double lmt-price)))
    (when aux-price  (set-field! o "auxPrice"   (double aux-price)))
    (set-field! o "tif"      (str tif))
    (set-field! o "transmit" (boolean transmit))
    (when parent-id  (set-field! o "parentId"   (int (long parent-id))))
    o))

(defn- create-wrapper-proxy [publish! request-registry next-order-id-atom]
  (let [wrapper-class (resolve-class "com.ib.client.EWrapper")
        loader (.getClassLoader wrapper-class)
        interfaces (into-array Class [wrapper-class])
        handler (reify java.lang.reflect.InvocationHandler
                  (invoke [_ _proxy method args]
                    (let [name (.getName method)
                          argv (vec (or args (object-array 0)))]
                      (case name
                        "error"
                        (publish! (enrich-error-event request-registry
                                                      (handler-error-event argv)))

                        "position"
                        (let [[account contract pos avg-cost] argv]
                          (publish!
                           (events/position->event
                            {:account account
                             :contract contract
                             :position pos
                             :avg-cost avg-cost})))

                        "positionEnd"
                        (publish! (events/position-end->event))

                        "accountSummary"
                        (let [[req-id account tag value currency] argv]
                          (publish!
                           (events/account-summary->event
                            {:req-id req-id
                             :account account
                             :tag tag
                             :value value
                             :currency currency})))

                        "accountSummaryEnd"
                        (publish! (events/account-summary-end->event
                                   {:req-id (first argv)}))

                        "openOrder"
                        (let [[order-id contract order order-state] argv]
                          (publish! (events/open-order->event
                                     {:order-id order-id
                                      :contract contract
                                      :order order
                                      :order-state order-state})))

                        "orderStatus"
                        (let [[order-id status filled remaining avg-fill-price perm-id parent-id
                               last-fill-price client-id why-held mkt-cap-price] argv]
                          (publish! (events/order-status->event
                                     {:order-id order-id
                                      :status status
                                      :filled filled
                                      :remaining remaining
                                      :avg-fill-price avg-fill-price
                                      :perm-id perm-id
                                      :parent-id parent-id
                                      :client-id client-id
                                      :last-fill-price last-fill-price
                                      :why-held why-held
                                      :mkt-cap-price mkt-cap-price})))

                        "openOrderEnd"
                        (publish! (events/open-order-end->event))

                        "updateAccountValue"
                        (let [[key value currency account] argv]
                          (publish!
                           (events/update-account-value->event
                            {:key key
                             :value value
                             :currency currency
                             :account account})))

                        "updateAccountTime"
                        (publish!
                         (events/update-account-time->event
                          {:time (first argv)}))

                        "updatePortfolio"
                        (let [[contract position market-price market-value average-cost unrealized-pnl realized-pnl account] argv]
                          (publish!
                           (events/update-portfolio->event
                            {:contract contract
                             :position position
                             :market-price market-price
                             :market-value market-value
                             :average-cost average-cost
                             :unrealized-pnl unrealized-pnl
                             :realized-pnl realized-pnl
                             :account account})))

                        "accountDownloadEnd"
                        (publish!
                         (events/account-download-end->event
                          {:account (first argv)}))

                        "connectionClosed"
                        (publish! (events/disconnected->event
                                   {:reason :connection-closed}))

                        "nextValidId"
                        (do
                          (when next-order-id-atom
                            (reset! next-order-id-atom (long (first argv))))
                          (publish! (events/next-valid-id->event
                                     {:order-id (first argv)})))

                        "tickPrice"
                        (let [[ticker-id field price _attrib] argv]
                          (publish! (events/tick-price->event
                                     {:req-id ticker-id
                                      :field  (int field)
                                      :price  price})))

                        "tickSnapshotEnd"
                        (publish! (events/tick-snapshot-end->event
                                   {:req-id (first argv)}))

                        "contractDetails"
                        (let [[req-id contract-details] argv]
                          (publish! (events/contract-details->event
                                     {:req-id           req-id
                                      :contract-details contract-details})))

                        "contractDetailsEnd"
                        (publish! (events/contract-details-end->event
                                   {:req-id (first argv)}))

                        nil)
                      (default-for-return-type (.getReturnType method)))))]
    (java.lang.reflect.Proxy/newProxyInstance loader interfaces handler)))

(defn- start-reader-loop! [client signal reader publish! on-disconnect]
  (doto
   (Thread.
    (fn []
      (try
        (while (and (invoke-method client "isConnected")
                    (not (Thread/interrupted)))
          (invoke-method signal "waitForSignal")
          (try
            (invoke-method reader "processMsgs")
            (catch Throwable t
              (publish! (events/error->event
                         {:message "Reader loop failed"
                          :raw t})))))
        (catch Throwable t
          (publish! (events/error->event
                     {:message "Reader thread crashed"
                      :raw t})))
        (finally
          (when on-disconnect
            (on-disconnect))))))
   (.setName "ib-reader-loop")
   (.setDaemon true)
   (.start)))

(declare start-reconnect-loop!)

(defn- attempt-reconnect! [conn on-disconnect]
  (let [{:keys [host port client-id publish! request-registry next-order-id]} conn
        signal-class (resolve-class "com.ib.client.EJavaSignal")
        client-class (resolve-class "com.ib.client.EClientSocket")
        reader-class (resolve-class "com.ib.client.EReader")
        wrapper (create-wrapper-proxy publish! request-registry next-order-id)
        signal (new-instance signal-class [])
        client (new-instance client-class [wrapper signal])]
    (try
      (invoke-method client "eConnect" host (int port) (int client-id))
      (if (invoke-method client "isConnected")
        (let [reader (new-instance reader-class [client signal])]
          (invoke-method reader "start")
          (maybe-start-api! client)
          (let [reader-thread (start-reader-loop! client signal reader publish! on-disconnect)]
            {:client client
             :reader reader
             :reader-thread reader-thread}))
        nil)
      (catch Throwable _
        nil))))

(defn- start-reconnect-loop! [conn on-disconnect]
  (let [{:keys [publish! manual-disconnect? reconnecting? host port client-id reconnect-opts]} conn
        {:keys [max-attempts initial-delay-ms max-delay-ms]} reconnect-opts]
    (async/go-loop [attempt 1
                    delay-ms initial-delay-ms]
      (cond
        @manual-disconnect?
        (reset! reconnecting? false)

        (> attempt max-attempts)
        (do
          (publish! (events/reconnect-failed->event {:attempts (dec attempt)}))
          (reset! reconnecting? false))

        :else
        (do
          (publish! (events/reconnecting->event {:attempt attempt :delay-ms delay-ms}))
          (async/<! (async/timeout delay-ms))
          (if @manual-disconnect?
            (reset! reconnecting? false)
            (let [result (async/<! (async/thread (attempt-reconnect! conn on-disconnect)))]
              (if result
                (do
                  (reset! (:client conn) (:client result))
                  (reset! (:reader conn) (:reader result))
                  (reset! (:reader-thread conn) (:reader-thread result))
                  (reset! reconnecting? false)
                  (publish! (events/reconnected->event
                             {:host host
                              :port port
                              :client-id client-id
                              :attempt attempt})))
                (recur (inc attempt)
                       (min max-delay-ms (* 2 delay-ms)))))))))))

(defn connect!
  "Connect to TWS or IB Gateway.

  Options:
  - `:host` (default `127.0.0.1`)
  - `:port` (default `7497`)
  - `:client-id` (default `0`)
  - `:event-buffer-size` (default 1024)
  - `:overflow-strategy` one of `:sliding` or `:dropping` (default `:sliding`)
  - `:auto-reconnect?` whether to auto-reconnect on drop (default `false`)
  - `:reconnect-opts` map with `:max-attempts` (default 10), `:initial-delay-ms` (default 1000),
    `:max-delay-ms` (default 60000)

  Returns connection map used by other functions in this namespace."
  [{:keys [host port client-id event-buffer-size overflow-strategy auto-reconnect? reconnect-opts]
    :or {host "127.0.0.1"
         port 7497
         client-id 0
         event-buffer-size events/default-event-buffer-size
         overflow-strategy events/default-overflow-strategy
         auto-reconnect? false
         reconnect-opts {}}}]
  (ensure-ib-classes!)
  (let [{:keys [events events-mult dropped-events publish!]} (events/create-event-bus
                                                              {:buffer-size event-buffer-size
                                                               :overflow-strategy overflow-strategy})
        request-registry (atom {})
        next-order-id (atom nil)
        wrapper (create-wrapper-proxy publish! request-registry next-order-id)
        signal-class (resolve-class "com.ib.client.EJavaSignal")
        client-class (resolve-class "com.ib.client.EClientSocket")
        reader-class (resolve-class "com.ib.client.EReader")
        signal (new-instance signal-class [])
        client-obj (new-instance client-class [wrapper signal])]
    (invoke-method client-obj "eConnect" host (int port) (int client-id))
    (when-not (invoke-method client-obj "isConnected")
      (throw (ex-info "Failed to connect to IB TWS/Gateway"
                      {:host host
                       :port port
                       :client-id client-id})))
    (let [reader-obj (new-instance reader-class [client-obj signal])
          _ (invoke-method reader-obj "start")
          _ (maybe-start-api! client-obj)
          client-atom (atom client-obj)
          reader-atom (atom reader-obj)
          reader-thread-atom (atom nil)
          manual-disconnect? (atom false)
          reconnecting? (atom false)
          merged-reconnect-opts (merge {:max-attempts 10
                                        :initial-delay-ms 1000
                                        :max-delay-ms 60000}
                                       reconnect-opts)
          conn {:host host
                :port port
                :client-id client-id
                :client client-atom
                :signal signal
                :reader reader-atom
                :reader-thread reader-thread-atom
                :manual-disconnect? manual-disconnect?
                :reconnecting? reconnecting?
                :auto-reconnect? auto-reconnect?
                :reconnect-opts merged-reconnect-opts
                :events events
                :events-mult events-mult
                :publish! publish!
                :dropped-events dropped-events
                :request-registry request-registry
                :next-order-id next-order-id
                :open-orders-snapshot-in-flight (atom false)
                :overflow-strategy overflow-strategy}]
      (letfn [(on-disconnect []
                (when (and (not @manual-disconnect?)
                           (compare-and-set! reconnecting? false true))
                  (start-reconnect-loop! conn on-disconnect)))]
        (let [reader-thread (start-reader-loop! client-obj signal reader-obj publish!
                                               (when auto-reconnect? on-disconnect))]
          (reset! reader-thread-atom reader-thread)
          (publish! (events/connected->event
                     {:host host
                      :port port
                      :client-id client-id}))
          conn)))))

(defn disconnect!
  "Disconnect from IB and close event resources."
  [{:keys [client reader-thread manual-disconnect? publish! events] :as conn}]
  (when manual-disconnect?
    (reset! manual-disconnect? true))
  (when publish!
    (publish! (events/disconnected->event
               {:reason :manual-disconnect})))
  (when client
    (try
      (invoke-method @client "eDisconnect")
      (catch Throwable _ nil)))
  (when reader-thread
    (try
      (.interrupt ^Thread @reader-thread)
      (catch Throwable _ nil)))
  (when events
    (async/close! events))
  (assoc conn :disconnected? true))

(defn req-positions!
  "Trigger `reqPositions()` on the IB client."
  [{:keys [client]}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method @client "reqPositions")
  true)

(defn req-open-orders!
  "Trigger `reqOpenOrders()` on the IB client."
  [{:keys [client]}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method @client "reqOpenOrders")
  true)

(defn req-all-open-orders!
  "Trigger `reqAllOpenOrders()` on the IB client."
  [{:keys [client]}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method @client "reqAllOpenOrders")
  true)

(defn req-account-summary!
  "Trigger `reqAccountSummary(reqId, group, tags)` on the IB client.

  Options:
  - `:req-id` required integer request id
  - `:group` default `\"All\"`
  - `:tags` string (comma-separated) or sequence of tags"
  [{:keys [client] :as conn} {:keys [req-id group tags]
                              :or {group default-account-summary-group
                                   tags default-account-summary-tags}}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "req-account-summary! requires integer :req-id" {:req-id req-id})))
  (let [tags-str (normalize-account-summary-tags tags)]
    (register-request! conn req-id {:type :account-summary
                                    :group group
                                    :tags tags-str})
    (try
      (invoke-method @client
                     "reqAccountSummary"
                     (int req-id)
                     (str group)
                     tags-str)
      (catch Throwable t
        (unregister-request! conn req-id)
        (throw t))))
  req-id)

(defn cancel-account-summary!
  "Cancel account summary subscription with `cancelAccountSummary(reqId)`."
  [{:keys [client] :as conn} req-id]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "cancel-account-summary! requires integer req-id" {:req-id req-id})))
  (invoke-method @client "cancelAccountSummary" (int req-id))
  (unregister-request! conn req-id)
  true)

(defn req-account-updates!
  "Start or update IB account updates subscription via `reqAccountUpdates`.

  Options:
  - `:account` account code (required by IB, usually DU... value)
  - `:subscribe?` defaults to true"
  [{:keys [client]} {:keys [account subscribe?]
                     :or {subscribe? true}}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (string? account)
    (throw (ex-info "req-account-updates! requires string :account" {:account account})))
  (invoke-method @client "reqAccountUpdates" (boolean subscribe?) account)
  true)

(defn cancel-account-updates!
  "Cancel IB account updates subscription for account."
  [conn account]
  (req-account-updates! conn {:account account
                              :subscribe? false}))

(defn req-ids!
  "Trigger `reqIds(1)` on the IB client to seed the next valid order ID.
  IB will respond via the `:ib/next-valid-id` event which automatically
  updates the internal counter used by `next-order-id!`."
  [{:keys [client]}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method @client "reqIds" (int 1))
  true)

(defn next-order-id!
  "Atomically return the current next-valid order ID and increment the counter.
  Throws if the counter has not been seeded (call `req-ids!` first)."
  [{:keys [next-order-id]}]
  (when-not next-order-id
    (throw (ex-info "Connection map missing :next-order-id. Was connect! used?" {})))
  (when (nil? @next-order-id)
    (throw (ex-info "No valid order ID received yet. Call req-ids! and wait for :ib/next-valid-id." {})))
  (let [id @next-order-id]
    (swap! next-order-id inc)
    id))

(defn place-order!
  "Submit an order via `placeOrder(orderId, contract, order)`.

  Options map:
  - `:order-id` integer order ID (optional — auto-allocated via `next-order-id!` when absent)
  - `:contract` `com.ib.client.Contract` instance *or* a map with keys
    `:symbol`, `:sec-type`, `:currency`, `:exchange`, `:primary-exch`, `:con-id`
  - `:order`    `com.ib.client.Order` instance *or* a map with keys
    `:action`, `:order-type`, `:total-quantity`, `:lmt-price`, `:aux-price`,
    `:tif`, `:transmit`, `:parent-id`

  Returns the order-id used."
  [{:keys [client] :as conn} {:keys [order-id contract order]}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (let [oid (or order-id (next-order-id! conn))]
    (when-not (integer? oid)
      (throw (ex-info "place-order! requires an integer :order-id" {:order-id oid})))
    (let [java-contract (if (map? contract) (map->contract contract) contract)
          java-order    (if (map? order)    (map->order order)    order)]
      (invoke-method @client "placeOrder" (int oid) java-contract java-order)
      oid)))

(defn cancel-order!
  "Cancel an open order via `cancelOrder(orderId)`.
  Tries the 2-arg variant (API 10.xx) first, falls back to 1-arg.
  Returns `true`."
  [{:keys [client]} order-id]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? order-id)
    (throw (ex-info "cancel-order! requires an integer order-id" {:order-id order-id})))
  (try
    (invoke-method @client "cancelOrder" (int order-id) "")
    (catch Throwable _
      (invoke-method @client "cancelOrder" (int order-id))))
  true)

(defn connected?
  "Return `true` if the IB client socket is currently connected."
  [{:keys [client]}]
  (boolean (and (some-> client deref)
                (try (invoke-method @client "isConnected")
                     (catch Throwable _ false)))))

(defn req-market-data-type!
  "Set the market data type for subsequent `req-mkt-data!` calls.

  Types: `1` = live, `2` = frozen, `3` = delayed, `4` = delayed-frozen.
  Use `4` for testing without a paid data subscription."
  [{:keys [client]} market-data-type]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method @client "reqMktDataType" (int market-data-type))
  true)

(defn req-mkt-data!
  "Request market data ticks for a contract via `reqMktData`.

  Options:
  - `:req-id`                 integer request id (required)
  - `:symbol`                 ticker symbol string
  - `:con-id`                 IB contract id (preferred; use instead of symbol)
  - `:sec-type`               default `\"STK\"`
  - `:exchange`               default `\"SMART\"`
  - `:primary-exch`           primary exchange (e.g. `\"NYSE\"`) for SMART routing
  - `:currency`               default `\"USD\"`
  - `:generic-tick-list`      default `\"\"`
  - `:snapshot`               boolean, default `true`
  - `:regulatory-snapshot`    boolean, default `false`

  Returns `true`. Listen on the event stream for `:ib/tick-price` and
  `:ib/tick-snapshot-end` events tagged with the same `:req-id`."
  [{:keys [client]}
   {:keys [req-id symbol con-id sec-type exchange primary-exch currency
           generic-tick-list snapshot regulatory-snapshot]
    :or {sec-type "STK" exchange "SMART" currency "USD"
         generic-tick-list "" snapshot true regulatory-snapshot false}}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "req-mkt-data! requires integer :req-id" {:req-id req-id})))
  (let [contract (map->contract {:symbol symbol :con-id con-id :sec-type sec-type
                                 :exchange exchange :primary-exch primary-exch
                                 :currency currency})]
    (try
      (invoke-method @client "reqMktData" (int req-id) contract
                     (str generic-tick-list) (boolean snapshot)
                     (boolean regulatory-snapshot) nil)
      (catch Throwable _
        (invoke-method @client "reqMktData" (int req-id) contract
                       (str generic-tick-list) (boolean snapshot)
                       (boolean regulatory-snapshot)))))
  true)

(defn cancel-mkt-data!
  "Cancel a market data subscription via `cancelMktData(reqId)`."
  [{:keys [client]} req-id]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "cancel-mkt-data! requires integer req-id" {:req-id req-id})))
  (invoke-method @client "cancelMktData" (int req-id))
  true)

(defn req-contract-details!
  "Request contract details via `reqContractDetails(reqId, contract)`.

  Options:
  - `:req-id`      integer request id (required)
  - `:symbol`      ticker symbol string
  - `:con-id`      IB contract id (preferred when known)
  - `:sec-type`    default `\"STK\"`
  - `:exchange`    default `\"SMART\"`
  - `:currency`    default `\"USD\"`

  Listen on the event stream for `:ib/contract-details` events (one per
  matching contract) followed by `:ib/contract-details-end`, all tagged
  with the same `:req-id`."
  [{:keys [client]}
   {:keys [req-id symbol con-id sec-type exchange currency]
    :or {sec-type "STK" exchange "SMART" currency "USD"}}]
  (when-not (some-> client deref)
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "req-contract-details! requires integer :req-id" {:req-id req-id})))
  (let [contract (map->contract {:symbol symbol :con-id con-id :sec-type sec-type
                                 :exchange exchange :currency currency})]
    (invoke-method @client "reqContractDetails" (int req-id) contract))
  true)

(defn events-chan
  "Return the shared event channel (primarily for diagnostics)."
  [{:keys [events]}]
  events)

(defn subscribe-events!
  "Create a subscriber channel tapped to the event stream.

  Options forwarded to `ib.events/subscribe!`."
  ([conn]
   (subscribe-events! conn {}))
  ([{:keys [events-mult]} opts]
   (when-not events-mult
     (throw (ex-info "Connection map missing :events-mult" {})))
   (events/subscribe! events-mult opts)))

(defn unsubscribe-events!
  "Untap a previously subscribed channel from the event stream."
  [{:keys [events-mult]} ch]
  (when events-mult
    (events/unsubscribe! events-mult ch))
  ch)

(defn dropped-event-count
  "Number of events that could not be enqueued (e.g. after channel closed)."
  [{:keys [dropped-events]}]
  (if dropped-events
    @dropped-events
    0))
