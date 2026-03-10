(ns ib.events
  "Event bus and normalization helpers for Interactive Brokers callback data."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]))

(def default-event-buffer-size
  "Default size for the bounded event channel."
  1024)

(def default-overflow-strategy
  "Default channel overflow strategy.

  `:sliding` keeps latest events and drops oldest when full.
  `:dropping` keeps oldest events and drops newest when full."
  :sliding)

(def event-schema-version
  "Version of the normalized IB event contract."
  "v1")

(def default-event-source
  "Default source marker for IB events."
  :ib/tws)

(defn now-ms
  "Return current timestamp in epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn base-event
  "Create a base event map with the unified schema keys.

  Required schema keys:
  - `:type`
  - `:source`
  - `:status`
  - `:request-id`
  - `:ts`
  - `:schema-version`"
  [type {:keys [source status request-id]
         :or {source default-event-source
              status :ok
              request-id nil}}]
  {:type type
   :source source
   :status status
   :request-id request-id
   :ts (now-ms)
   :schema-version event-schema-version})

(defn- normalize-overflow-strategy [strategy]
  (if (#{:sliding :dropping} strategy)
    strategy
    default-overflow-strategy))

(defn- make-buffer [size strategy]
  (case strategy
    :dropping (async/dropping-buffer size)
    (async/sliding-buffer size)))

(defn create-event-bus
  "Create bounded event infrastructure.

  Options:
  - `:buffer-size` (default 1024)
  - `:overflow-strategy` one of `:sliding` or `:dropping` (default `:sliding`)

  Returns map with `:events`, `:events-mult`, `:dropped-events` and `:publish!`.
  `:publish!` never blocks callback threads."
  [{:keys [buffer-size overflow-strategy]
    :or {buffer-size default-event-buffer-size
         overflow-strategy default-overflow-strategy}}]
  (let [strategy (normalize-overflow-strategy overflow-strategy)
        events (async/chan (make-buffer buffer-size strategy))
        events-mult (async/mult events)
        dropped-events (atom 0)
        publish! (fn [event]
                   (let [ok? (async/offer! events event)]
                     (when-not ok?
                       (swap! dropped-events inc))
                     ok?))]
    {:events events
     :events-mult events-mult
     :dropped-events dropped-events
     :publish! publish!
     :overflow-strategy strategy
     :buffer-size buffer-size}))

(defn subscribe!
  "Tap event stream and return a dedicated subscriber channel.

  Options:
  - `:buffer-size` for subscriber channel (default 256)
  - `:close?` whether subscriber closes with source (default true)"
  ([events-mult]
   (subscribe! events-mult {}))
  ([events-mult {:keys [buffer-size close?]
                 :or {buffer-size 256
                      close? true}}]
   (let [ch (async/chan (async/sliding-buffer buffer-size))]
     (async/tap events-mult ch close?)
     ch)))

(defn unsubscribe!
  "Untap a subscriber channel from event mult."
  [events-mult ch]
  (async/untap events-mult ch)
  ch)

(defn- try-zero-arg-method [obj method-name]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod obj method-name (object-array 0))
    (catch Throwable _
      nil)))

(defn- pick-value [m candidates]
  (some (fn [k]
          (let [v (get m k ::missing)]
            (when-not (= v ::missing) v)))
        candidates))

(defn- parse-long-safe [value]
  (cond
    (nil? value) nil
    (integer? value) (long value)
    (number? value) (long value)
    (string? value) (try
                      (Long/parseLong value)
                      (catch Throwable _ nil))
    :else nil))

(defn- parse-double-safe [value]
  (cond
    (nil? value) nil
    (number? value) (double value)
    (string? value) (try
                      (Double/parseDouble value)
                      (catch Throwable _ nil))
    :else (some-> value str parse-double-safe)))

(defn- parse-boolean-safe [value]
  (cond
    (nil? value) nil
    (instance? Boolean value) value
    (string? value) (contains? #{"true" "1" "yes" "y"} (str/lower-case value))
    :else (boolean value)))

(defn contract->map
  "Normalize IB Contract data into a stable map.

  Output keys are always:
  `:conId :symbol :secType :currency :exchange`"
  [contract]
  (cond
    (nil? contract)
    {:conId nil
     :symbol nil
     :secType nil
     :currency nil
     :exchange nil}

    (map? contract)
    {:conId (parse-long-safe (pick-value contract [:conId :con-id :conid "conId" "conid"]))
     :symbol (pick-value contract [:symbol "symbol"])
     :secType (some-> (pick-value contract [:secType :sec-type "secType" "secType" "sectype"]) str)
     :currency (some-> (pick-value contract [:currency "currency"]) str)
     :exchange (some-> (pick-value contract [:exchange "exchange"]) str)}

    :else
    {:conId (parse-long-safe
             (or (try-zero-arg-method contract "conid")
                 (try-zero-arg-method contract "conId")))
     :symbol (some-> (or (try-zero-arg-method contract "symbol")
                         (try-zero-arg-method contract "localSymbol"))
                     str)
     :secType (some-> (or (try-zero-arg-method contract "secType")
                          (try-zero-arg-method contract "getSecType"))
                      str)
     :currency (some-> (or (try-zero-arg-method contract "currency")
                           (try-zero-arg-method contract "getCurrency"))
                       str)
     :exchange (some-> (or (try-zero-arg-method contract "exchange")
                           (try-zero-arg-method contract "primaryExch")
                           (try-zero-arg-method contract "getExchange"))
                       str)}))

(defn- order-field [order key-candidates method-candidates]
  (cond
    (map? order) (pick-value order key-candidates)
    :else (some #(try-zero-arg-method order %) method-candidates)))

(defn- order->map [order]
  {:action (some-> (order-field order [:action "action"] ["action"]) str)
   :orderType (some-> (order-field order [:orderType :order-type "orderType"] ["orderType"]) str)
   :totalQuantity (parse-double-safe
                   (order-field order
                                [:totalQuantity :total-quantity "totalQuantity"]
                                ["totalQuantity"]))
   :lmtPrice (parse-double-safe
              (order-field order
                           [:lmtPrice :limit-price "lmtPrice"]
                           ["lmtPrice"]))
   :auxPrice (parse-double-safe
              (order-field order
                           [:auxPrice :aux-price "auxPrice"]
                           ["auxPrice"]))
   :tif (some-> (order-field order [:tif "tif"] ["tif"]) str)
   :transmit (parse-boolean-safe
              (order-field order [:transmit "transmit"] ["transmit"]))
   :parentId (parse-long-safe
              (order-field order [:parentId :parent-id "parentId"] ["parentId"]))})

(defn- order-perm-id [order]
  (parse-long-safe
   (order-field order [:permId :perm-id "permId"] ["permId"])))

(defn- order-account [order]
  (some-> (order-field order [:account "account"] ["account"]) str))

(defn- order-state-field [order-state key-candidates method-candidates]
  (cond
    (map? order-state) (pick-value order-state key-candidates)
    :else (some #(try-zero-arg-method order-state %) method-candidates)))

(defn- order-state->map [order-state]
  {:status (some-> (order-state-field order-state [:status "status"] ["status"]) str)
   :commission (parse-double-safe
                (order-state-field order-state
                                   [:commission "commission"]
                                   ["commission"]))
   :warningText (some-> (order-state-field order-state
                                           [:warningText :warning-text "warningText"]
                                           ["warningText"])
                        str)})

(defn position->event
  "Build normalized `:ib/position` event from IB callback payload."
  [{:keys [account contract position avg-cost]}]
  (merge
   (base-event :ib/position {:status :ok})
   {:account account
    :contract (contract->map contract)
    :position (parse-double-safe position)
    :avg-cost (parse-double-safe avg-cost)}))

(defn account-summary->event
  "Build normalized `:ib/account-summary` event from IB callback payload."
  [{:keys [req-id account tag value currency]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/account-summary {:status :ok
                                      :request-id rid})
     {:req-id rid
      :account (some-> account str)
      :tag (some-> tag str)
      :value (some-> value str)
      :currency (some-> currency str)})))

(defn update-account-value->event
  "Build normalized `:ib/update-account-value` event."
  [{:keys [key value currency account]}]
  (merge
   (base-event :ib/update-account-value {:status :ok})
   {:key (some-> key str)
    :value (some-> value str)
    :currency (some-> currency str)
    :account (some-> account str)}))

(defn update-account-time->event
  "Build normalized `:ib/update-account-time` event."
  [{:keys [time]}]
  (merge
   (base-event :ib/update-account-time {:status :ok})
   {:time (some-> time str)}))

(defn update-portfolio->event
  "Build normalized `:ib/update-portfolio` event."
  [{:keys [contract position market-price market-value average-cost unrealized-pnl realized-pnl account]}]
  (merge
   (base-event :ib/update-portfolio {:status :ok})
   {:contract (contract->map contract)
    :position (parse-double-safe position)
    :market-price (parse-double-safe market-price)
    :market-value (parse-double-safe market-value)
    :average-cost (parse-double-safe average-cost)
    :unrealized-pnl (parse-double-safe unrealized-pnl)
    :realized-pnl (parse-double-safe realized-pnl)
    :account (some-> account str)}))

(defn account-download-end->event
  "Build normalized `:ib/account-download-end` event."
  [{:keys [account]}]
  (merge
   (base-event :ib/account-download-end {:status :ok})
   {:account (some-> account str)}))

(defn account-summary-end->event
  "Build normalized `:ib/account-summary-end` event."
  [{:keys [req-id]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/account-summary-end {:status :ok
                                          :request-id rid})
     {:req-id rid})))

(defn position-end->event
  "Build normalized `:ib/position-end` event."
  []
  (base-event :ib/position-end {:status :ok}))

(defn connected->event
  "Build normalized `:ib/connected` event."
  [{:keys [host port client-id]}]
  (merge
   (base-event :ib/connected {:status :ok})
   {:host host
    :port port
    :client-id client-id}))

(defn disconnected->event
  "Build normalized `:ib/disconnected` event."
  [{:keys [reason]}]
  (merge
   (base-event :ib/disconnected {:status :ok})
   {:reason reason}))

(defn next-valid-id->event
  "Build normalized `:ib/next-valid-id` event."
  [{:keys [order-id]}]
  (merge
   (base-event :ib/next-valid-id {:status :ok})
   {:order-id order-id}))

(defn open-order->event
  "Build normalized `:ib/open-order` event."
  [{:keys [order-id contract order order-state]}]
  (let [oid (parse-long-safe order-id)]
    (merge
     (base-event :ib/open-order {:status :ok})
     {:order-id oid
      :perm-id (order-perm-id order)
      :account (order-account order)
      :contract (contract->map contract)
      :order (order->map order)
      :order-state (order-state->map order-state)})))

(defn order-status->event
  "Build normalized `:ib/order-status` event."
  [{:keys [order-id status filled remaining avg-fill-price perm-id parent-id client-id
           last-fill-price why-held mkt-cap-price]}]
  (let [oid (parse-long-safe order-id)]
    (merge
     (base-event :ib/order-status {:status :ok})
     {:order-id oid
      :status-text (some-> status str)
      :filled (parse-double-safe filled)
      :remaining (parse-double-safe remaining)
      :avg-fill-price (parse-double-safe avg-fill-price)
      :perm-id (parse-long-safe perm-id)
      :parent-id (parse-long-safe parent-id)
      :client-id (parse-long-safe client-id)
      :last-fill-price (parse-double-safe last-fill-price)
      :why-held (some-> why-held str)
      :mkt-cap-price (parse-double-safe mkt-cap-price)})))

(defn open-order-end->event
  "Build normalized `:ib/open-order-end` event."
  []
  (base-event :ib/open-order-end {:status :ok}))

(defn error->event
  "Build normalized `:ib/error` event from IB callback payload."
  [{:keys [id code message raw]}]
  (let [request-id (parse-long-safe id)]
    (merge
     (base-event :ib/error {:status :error
                            :request-id request-id})
     {:id id
      :code code
      :message (some-> message str/trim)
      :raw raw})))

(defn reconnecting->event
  "Build normalized `:ib/reconnecting` event emitted at the start of each reconnect attempt."
  [{:keys [attempt delay-ms]}]
  (merge
   (base-event :ib/reconnecting {:status :ok})
   {:attempt attempt
    :delay-ms delay-ms}))

(defn reconnected->event
  "Build normalized `:ib/reconnected` event emitted when reconnect succeeds."
  [{:keys [host port client-id attempt]}]
  (merge
   (base-event :ib/reconnected {:status :ok})
   {:host host
    :port port
    :client-id client-id
    :attempt attempt}))

(defn reconnect-failed->event
  "Build normalized `:ib/reconnect-failed` event emitted when all reconnect attempts are exhausted."
  [{:keys [attempts]}]
  (merge
   (base-event :ib/reconnect-failed {:status :error})
   {:attempts attempts}))

(def tick-type->key
  "IB tick type integer → semantic keyword used in `:ib/tick-price` events."
  {1  :bid
   2  :ask
   4  :last
   6  :high
   7  :low
   9  :close
   14 :open})

(defn tick-price->event
  "Build normalized `:ib/tick-price` event from IB `tickPrice` callback.
  `:field-key` is the semantic keyword (`:bid`, `:ask`, `:last`, etc.) or
  `nil` for unrecognised tick types."
  [{:keys [req-id field price]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/tick-price {:status :ok :request-id rid})
     {:req-id    rid
      :field     field
      :field-key (get tick-type->key field)
      :price     (parse-double-safe price)})))

(defn tick-snapshot-end->event
  "Build normalized `:ib/tick-snapshot-end` event from IB `tickSnapshotEnd` callback."
  [{:keys [req-id]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/tick-snapshot-end {:status :ok :request-id rid})
     {:req-id rid})))

(defn- contract-details-field [cd method-names]
  (some #(try-zero-arg-method cd %) method-names))

(defn contract-details->map
  "Normalize IB ContractDetails Java object into a stable map.

  Inner contract is normalized via `contract->map`.
  Fields extracted: `:long-name`, `:min-tick`, `:trading-hours`,
  `:liquid-hours`, `:time-zone-id`."
  [cd]
  (when cd
    (let [inner (or (try-zero-arg-method cd "contract")
                    (try-zero-arg-method cd "m_contract"))]
      {:contract      (when inner (contract->map inner))
       :long-name     (some-> (contract-details-field cd ["longName" "getLongName"]) str)
       :min-tick      (parse-double-safe (contract-details-field cd ["minTick" "getMinTick"]))
       :trading-hours (some-> (contract-details-field cd ["tradingHours" "getTradingHours"]) str)
       :liquid-hours  (some-> (contract-details-field cd ["liquidHours" "getLiquidHours"]) str)
       :time-zone-id  (some-> (contract-details-field cd ["timeZoneId" "getTimeZoneId"]) str)})))

(defn contract-details->event
  "Build normalized `:ib/contract-details` event from IB `contractDetails` callback."
  [{:keys [req-id contract-details]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/contract-details {:status :ok :request-id rid})
     {:req-id           rid
      :contract-details (contract-details->map contract-details)})))

(defn contract-details-end->event
  "Build normalized `:ib/contract-details-end` event from IB `contractDetailsEnd` callback."
  [{:keys [req-id]}]
  (let [rid (parse-long-safe req-id)]
    (merge
     (base-event :ib/contract-details-end {:status :ok :request-id rid})
     {:req-id rid})))
