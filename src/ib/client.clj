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

(defn- create-wrapper-proxy [publish! request-registry]
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
                        (publish! {:type :ib/position-end
                                   :ts (events/now-ms)})

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
                        (publish! {:type :ib/account-summary-end
                                   :ts (events/now-ms)
                                   :req-id (first argv)})

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
                        (publish! {:type :ib/disconnected
                                   :ts (events/now-ms)})

                        "nextValidId"
                        (publish! {:type :ib/next-valid-id
                                   :ts (events/now-ms)
                                   :order-id (first argv)})

                        nil)
                      (default-for-return-type (.getReturnType method)))))]
    (java.lang.reflect.Proxy/newProxyInstance loader interfaces handler)))

(defn- start-reader-loop! [client signal reader publish!]
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
                      :raw t}))))))
   (.setName "ib-reader-loop")
   (.setDaemon true)
   (.start)))

(defn connect!
  "Connect to TWS or IB Gateway.

  Options:
  - `:host` (default `127.0.0.1`)
  - `:port` (default `7497`)
  - `:client-id` (default `0`)
  - `:event-buffer-size` (default 1024)
  - `:overflow-strategy` one of `:sliding` or `:dropping` (default `:sliding`)

  Returns connection map used by other functions in this namespace."
  [{:keys [host port client-id event-buffer-size overflow-strategy]
    :or {host "127.0.0.1"
         port 7497
         client-id 0
         event-buffer-size events/default-event-buffer-size
         overflow-strategy events/default-overflow-strategy}}]
  (ensure-ib-classes!)
  (let [{:keys [events events-mult dropped-events publish!]} (events/create-event-bus
                                                              {:buffer-size event-buffer-size
                                                               :overflow-strategy overflow-strategy})
        request-registry (atom {})
        wrapper (create-wrapper-proxy publish! request-registry)
        signal-class (resolve-class "com.ib.client.EJavaSignal")
        client-class (resolve-class "com.ib.client.EClientSocket")
        reader-class (resolve-class "com.ib.client.EReader")
        signal (new-instance signal-class [])
        client (new-instance client-class [wrapper signal])]
    (invoke-method client "eConnect" host (int port) (int client-id))
    (when-not (invoke-method client "isConnected")
      (throw (ex-info "Failed to connect to IB TWS/Gateway"
                      {:host host
                       :port port
                       :client-id client-id})))
    (let [reader (new-instance reader-class [client signal])]
      (invoke-method reader "start")
      (maybe-start-api! client)
      (let [reader-thread (start-reader-loop! client signal reader publish!)
            conn {:host host
                  :port port
                  :client-id client-id
                  :client client
                  :signal signal
                  :reader reader
                  :reader-thread reader-thread
                  :events events
                  :events-mult events-mult
                  :publish! publish!
                  :dropped-events dropped-events
                  :request-registry request-registry
                  :overflow-strategy overflow-strategy}]
        (publish! {:type :ib/connected
                   :ts (events/now-ms)
                   :host host
                   :port port
                   :client-id client-id})
        conn))))

(defn disconnect!
  "Disconnect from IB and close event resources."
  [{:keys [client reader-thread publish! events] :as conn}]
  (when publish!
    (publish! {:type :ib/disconnected
               :ts (events/now-ms)
               :reason :manual-disconnect}))
  (when client
    (try
      (invoke-method client "eDisconnect")
      (catch Throwable _ nil)))
  (when reader-thread
    (try
      (.interrupt ^Thread reader-thread)
      (catch Throwable _ nil)))
  (when events
    (async/close! events))
  (assoc conn :disconnected? true))

(defn req-positions!
  "Trigger `reqPositions()` on the IB client."
  [{:keys [client]}]
  (when-not client
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (invoke-method client "reqPositions")
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
  (when-not client
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "req-account-summary! requires integer :req-id" {:req-id req-id})))
  (let [tags-str (normalize-account-summary-tags tags)]
    (register-request! conn req-id {:type :account-summary
                                    :group group
                                    :tags tags-str})
    (try
      (invoke-method client
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
  (when-not client
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (integer? req-id)
    (throw (ex-info "cancel-account-summary! requires integer req-id" {:req-id req-id})))
  (invoke-method client "cancelAccountSummary" (int req-id))
  (unregister-request! conn req-id)
  true)

(defn req-account-updates!
  "Start or update IB account updates subscription via `reqAccountUpdates`.

  Options:
  - `:account` account code (required by IB, usually DU... value)
  - `:subscribe?` defaults to true"
  [{:keys [client]} {:keys [account subscribe?]
                     :or {subscribe? true}}]
  (when-not client
    (throw (ex-info "Connection map does not contain a client instance" {})))
  (when-not (string? account)
    (throw (ex-info "req-account-updates! requires string :account" {:account account})))
  (invoke-method client "reqAccountUpdates" (boolean subscribe?) account)
  true)

(defn cancel-account-updates!
  "Cancel IB account updates subscription for account."
  [conn account]
  (req-account-updates! conn {:account account
                              :subscribe? false}))

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
