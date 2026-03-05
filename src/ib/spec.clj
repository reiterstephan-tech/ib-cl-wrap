(ns ib.spec
  "Project-wide specs and function contracts for ib-cl-wrap."
  (:require [clojure.core.async.impl.protocols :as async-proto]
            [clojure.spec.alpha :as s]
            [ib.account]
            [ib.client]
            [ib.events]
            [ib.open-orders]
            [ib.positions]))

(defn channel?
  "True when value is a core.async channel/read port."
  [x]
  (satisfies? async-proto/ReadPort x))

(defn atom?
  "True when value is an atom-like reference used in this project."
  [x]
  (instance? clojure.lang.IAtom x))

(s/def :ib/host (s/and string? not-empty))
(s/def :ib/port (s/and int? #(<= 1 % 65535)))
(s/def :ib/client-id (s/and int? (complement neg?)))
(s/def :ib/timeout-ms (s/and int? pos?))
(s/def :ib/buffer-size (s/and int? pos?))
(s/def :ib/timestamp-ms (s/and int? (complement neg?)))
(s/def :ib/request-id (s/and int? (complement neg?)))
(s/def :ib/req-id (s/and int? (complement neg?)))
(s/def :ib/group string?)
(s/def :ib/tags (s/or :csv string? :seq (s/coll-of string? :kind sequential? :min-count 1)))
(s/def :ib/overflow-strategy #{:sliding :dropping})
(s/def :ib/channel channel?)
(s/def :ib/open-orders-mode #{:open :all})
(s/def :ib/mode #{:open :all})
(s/def :ib/tap-buffer-size (s/and int? pos?))
(s/def :ib/event-buffer-size (s/and int? pos?))
(s/def :ib/auto-reconnect? boolean?)
(s/def :ib/max-attempts (s/and int? pos?))
(s/def :ib/initial-delay-ms (s/and int? pos?))
(s/def :ib/max-delay-ms (s/and int? pos?))
(s/def :ib/ok boolean?)
(s/def :ib/values map?)
(s/def :ib/orders (s/coll-of :ib.result/open-order :kind vector?))
(s/def :ib/account string?)
(s/def :ib/subscribe? boolean?)
(s/def :ib/action string?)
(s/def :ib/order-type string?)
(s/def :ib/market-data-type (s/and int? #(<= 1 % 4)))
(s/def :ib/req-id (s/and int? (complement neg?)))
(s/def :ib/field int?)
(s/def :ib/field-key (s/nilable keyword?))
(s/def :ib/price (s/nilable number?))

(s/def :ib.contract/conId (s/nilable int?))
(s/def :ib.contract/symbol (s/nilable string?))
(s/def :ib.contract/secType (s/nilable string?))
(s/def :ib.contract/currency (s/nilable string?))
(s/def :ib.contract/exchange (s/nilable string?))
(s/def :ib.event/contract
  (s/keys :req-un [:ib.contract/conId
                   :ib.contract/symbol
                   :ib.contract/secType
                   :ib.contract/currency
                   :ib.contract/exchange]))

(s/def :ib.event/type keyword?)
(s/def :ib.event/source keyword?)
(s/def :ib.event/status keyword?)
(s/def :ib.event/request-id (s/nilable int?))
(s/def :ib.event/ts :ib/timestamp-ms)
(s/def :ib.event/schema-version string?)

(s/def :ib.event/base
  (s/keys :req-un [:ib.event/type
                   :ib.event/source
                   :ib.event/status
                   :ib.event/request-id
                   :ib.event/ts
                   :ib.event/schema-version]))

(s/def :ib.event/order-id (s/nilable int?))
(s/def :ib.event/perm-id (s/nilable int?))
(s/def :ib.event/account (s/nilable string?))
(s/def :ib.event/reason keyword?)
(s/def :ib.event/host string?)
(s/def :ib.event/port :ib/port)
(s/def :ib.event/client-id (s/nilable int?))
(s/def :ib.event/status-text (s/nilable string?))
(s/def :ib.event/filled (s/nilable number?))
(s/def :ib.event/remaining (s/nilable number?))
(s/def :ib.event/avg-fill-price (s/nilable number?))
(s/def :ib.event/parent-id (s/nilable int?))
(s/def :ib.event/last-fill-price (s/nilable number?))
(s/def :ib.event/why-held (s/nilable string?))
(s/def :ib.event/mkt-cap-price (s/nilable number?))
(s/def :ib.event/floating (s/nilable number?))
(s/def :ib.event/attempt (s/and int? pos?))
(s/def :ib.event/attempts (s/and int? (complement neg?)))
(s/def :ib.event/delay-ms (s/and int? (complement neg?)))

(s/def :ib.order/action (s/nilable string?))
(s/def :ib.order/orderType (s/nilable string?))
(s/def :ib.order/totalQuantity (s/nilable number?))
(s/def :ib.order/lmtPrice (s/nilable number?))
(s/def :ib.order/auxPrice (s/nilable number?))
(s/def :ib.order/tif (s/nilable string?))
(s/def :ib.order/transmit (s/nilable boolean?))
(s/def :ib.order/parentId (s/nilable int?))
(s/def :ib.event/order
  (s/keys :req-un [:ib.order/action
                   :ib.order/orderType
                   :ib.order/totalQuantity
                   :ib.order/lmtPrice
                   :ib.order/auxPrice
                   :ib.order/tif
                   :ib.order/transmit
                   :ib.order/parentId]))

(s/def :ib.order-state/status (s/nilable string?))
(s/def :ib.order-state/commission (s/nilable number?))
(s/def :ib.order-state/warningText (s/nilable string?))
(s/def :ib.event/order-state
  (s/keys :req-un [:ib.order-state/status
                   :ib.order-state/commission
                   :ib.order-state/warningText]))

(defmulti event-dispatch :type)

(defmethod event-dispatch :ib/connected [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/host :ib.event/port :ib.event/client-id])))

(defmethod event-dispatch :ib/disconnected [_]
  (s/and :ib.event/base
         (s/keys :opt-un [:ib.event/reason])))

(defmethod event-dispatch :ib/error [_]
  (s/and :ib.event/base
         (s/keys :opt-un [:ib.event/order-id])))

(defmethod event-dispatch :ib/position [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/account :ib.event/contract]
                 :opt-un [:ib.event/floating])))

(defmethod event-dispatch :ib/position-end [_]
  :ib.event/base)

(defmethod event-dispatch :ib/account-summary [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib/request-id]
                 :opt-un [:ib.event/account])))

(defmethod event-dispatch :ib/account-summary-end [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib/request-id])))

(defmethod event-dispatch :ib/update-account-value [_]
  (s/and :ib.event/base
         (s/keys :opt-un [:ib.event/account])))

(defmethod event-dispatch :ib/update-account-time [_]
  :ib.event/base)

(defmethod event-dispatch :ib/update-portfolio [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/contract]
                 :opt-un [:ib.event/account])))

(defmethod event-dispatch :ib/account-download-end [_]
  (s/and :ib.event/base
         (s/keys :opt-un [:ib.event/account])))

(defmethod event-dispatch :ib/next-valid-id [_]
  :ib.event/base)

(defmethod event-dispatch :ib/open-order [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/order-id :ib.event/contract :ib.event/order :ib.event/order-state]
                 :opt-un [:ib.event/perm-id :ib.event/account])))

(defmethod event-dispatch :ib/order-status [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/order-id :ib.event/status-text]
                 :opt-un [:ib.event/filled
                          :ib.event/remaining
                          :ib.event/avg-fill-price
                          :ib.event/perm-id
                          :ib.event/parent-id
                          :ib.event/client-id
                          :ib.event/last-fill-price
                          :ib.event/why-held
                          :ib.event/mkt-cap-price])))

(defmethod event-dispatch :ib/open-order-end [_]
  :ib.event/base)

(defmethod event-dispatch :ib/reconnecting [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/attempt :ib.event/delay-ms])))

(defmethod event-dispatch :ib/reconnected [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/host :ib.event/port :ib.event/client-id :ib.event/attempt])))

(defmethod event-dispatch :ib/reconnect-failed [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib.event/attempts])))

(defmethod event-dispatch :ib/tick-price [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib/req-id :ib/field]
                 :opt-un [:ib/field-key :ib/price])))

(defmethod event-dispatch :ib/tick-snapshot-end [_]
  (s/and :ib.event/base
         (s/keys :req-un [:ib/req-id])))

(defmethod event-dispatch :default [_]
  :ib.event/base)

(s/def :ib/event (s/multi-spec event-dispatch :type))

(s/def :ib.result/positions-ok (s/coll-of :ib/event :kind vector?))
(s/def :ib.result/error-map (s/and map? #(= :ib/error (:type %))))
(s/def :ib.result/positions-snapshot (s/or :ok :ib.result/positions-ok
                                           :error :ib.result/error-map))

(s/def :ib.result/account-values map?)
(s/def :ib.result/account-summary-ok
  (s/and (s/keys :req-un [:ib/ok :ib/req-id :ib/values :ib.event/ts])
         #(true? (:ok %))))
(s/def :ib.result/account-summary-error
  (s/and (s/keys :req-un [:ib/ok :ib.event/ts]
                 :opt-un [:ib/req-id])
         #(false? (:ok %))))
(s/def :ib.result/account-summary
  (s/or :ok :ib.result/account-summary-ok
        :error :ib.result/account-summary-error))

(s/def :ib.result/open-order (s/and map? #(= :ib/open-order (:type %))))
(s/def :ib.result/open-orders-ok
  (s/and (s/keys :req-un [:ib/ok :ib.event/ts]
                 :opt-un [:ib/orders])
         #(true? (:ok %))))
(s/def :ib.result/open-orders-error
  (s/and (s/keys :req-un [:ib/ok :ib.event/ts])
         #(false? (:ok %))))
(s/def :ib.result/open-orders-snapshot
  (s/or :ok :ib.result/open-orders-ok
        :error :ib.result/open-orders-error))

(s/def :ib.config/reconnect-opts
  (s/keys :opt-un [:ib/max-attempts :ib/initial-delay-ms :ib/max-delay-ms]))
(s/def :ib.config/connect-opts
  (s/keys :opt-un [:ib/host :ib/port :ib/client-id :ib/event-buffer-size
                   :ib/overflow-strategy :ib/auto-reconnect? :ib.config/reconnect-opts]))
(s/def :ib.config/subscribe-opts
  (s/keys :opt-un [:ib/buffer-size :ib/tap-buffer-size]))
(s/def :ib.config/positions-opts
  (s/keys :opt-un [:ib/timeout-ms :ib/tap-buffer-size]))
(s/def :ib.config/account-summary-req-opts
  (s/keys :req-un [:ib/req-id]
          :opt-un [:ib/group :ib/tags]))
(s/def :ib.config/account-summary-snapshot-opts
  (s/keys :opt-un [:ib/group :ib/tags :ib/req-id :ib/timeout-ms :ib/tap-buffer-size]))
(s/def :ib.config/open-orders-snapshot-opts
  (s/keys :opt-un [:ib/mode :ib/timeout-ms :ib/tap-buffer-size]))
(s/def :ib.config/account-updates-opts
  (s/keys :req-un [:ib/account]
          :opt-un [:ib/subscribe?]))

(s/def :ib.conn/with-client (s/and map? #(contains? % :client)))
(s/def :ib.conn/with-events (s/and map? #(contains? % :events-mult)))
(s/def :ib.conn/with-request-registry (s/and map? #(contains? % :request-registry)))
(s/def :ib.conn/with-open-orders-guard (s/and map? #(contains? % :open-orders-snapshot-in-flight)))
(s/def :ib.conn/with-reconnect-guard (s/and map? #(contains? % :reconnecting?)))
(s/def :ib.conn/with-order-id-counter (s/and map? #(contains? % :next-order-id)))
(s/def :ib.conn/any map?)

(s/fdef ib.client/connect!
  :args (s/cat :opts :ib.config/connect-opts)
  :ret :ib.conn/any)

(s/fdef ib.client/disconnect!
  :args (s/cat :conn :ib.conn/any)
  :ret :ib.conn/any)

(s/fdef ib.client/events-chan
  :args (s/cat :conn :ib.conn/any)
  :ret (s/nilable :ib/channel))

(s/fdef ib.client/subscribe-events!
  :args (s/or :arity-1 (s/cat :conn :ib.conn/with-events)
              :arity-2 (s/cat :conn :ib.conn/with-events :opts :ib.config/subscribe-opts))
  :ret :ib/channel)

(s/fdef ib.client/unsubscribe-events!
  :args (s/cat :conn :ib.conn/with-events :ch :ib/channel)
  :ret :ib/channel)

(s/fdef ib.client/req-positions!
  :args (s/cat :conn :ib.conn/with-client)
  :ret true?)

(s/fdef ib.positions/positions-snapshot!
  :args (s/or :arity-1 (s/cat :conn :ib.conn/with-events)
              :arity-2 (s/cat :conn :ib.conn/with-events :opts :ib.config/positions-opts))
  :ret :ib/channel)

(s/fdef ib.client/req-account-summary!
  :args (s/cat :conn :ib.conn/with-request-registry :opts :ib.config/account-summary-req-opts)
  :ret :ib/request-id)

(s/fdef ib.client/cancel-account-summary!
  :args (s/cat :conn :ib.conn/with-request-registry :req-id :ib/request-id)
  :ret true?)

(s/fdef ib.account/account-summary-snapshot!
  :args (s/or :arity-1 (s/cat :conn :ib.conn/with-request-registry)
              :arity-2 (s/cat :conn :ib.conn/with-request-registry :opts :ib.config/account-summary-snapshot-opts))
  :ret :ib/channel)

(s/fdef ib.client/req-open-orders!
  :args (s/cat :conn :ib.conn/with-client)
  :ret true?)

(s/fdef ib.client/req-all-open-orders!
  :args (s/cat :conn :ib.conn/with-client)
  :ret true?)

(s/fdef ib.client/req-account-updates!
  :args (s/cat :conn :ib.conn/with-client :opts :ib.config/account-updates-opts)
  :ret true?)

(s/fdef ib.client/cancel-account-updates!
  :args (s/cat :conn :ib.conn/with-client :account :ib/account)
  :ret true?)

(s/fdef ib.client/connected?
  :args (s/cat :conn map?)
  :ret boolean?)

(s/fdef ib.client/req-market-data-type!
  :args (s/cat :conn :ib.conn/with-client :market-data-type :ib/market-data-type)
  :ret true?)

(s/fdef ib.client/req-mkt-data!
  :args (s/cat :conn :ib.conn/with-client :opts map?)
  :ret true?)

(s/fdef ib.client/cancel-mkt-data!
  :args (s/cat :conn :ib.conn/with-client :req-id :ib/req-id)
  :ret true?)

(s/fdef ib.client/req-ids!
  :args (s/cat :conn :ib.conn/with-client)
  :ret true?)

(s/fdef ib.client/next-order-id!
  :args (s/cat :conn :ib.conn/with-order-id-counter)
  :ret int?)

(s/fdef ib.client/place-order!
  :args (s/cat :conn :ib.conn/with-client :opts map?)
  :ret int?)

(s/fdef ib.client/cancel-order!
  :args (s/cat :conn :ib.conn/with-client :order-id int?)
  :ret true?)

(s/fdef ib.client/register-request!
  :args (s/cat :conn :ib.conn/with-request-registry :req-id :ib/request-id :request map?)
  :ret :ib/request-id)

(s/fdef ib.client/unregister-request!
  :args (s/cat :conn map? :req-id :ib/request-id)
  :ret true?)

(s/fdef ib.client/request-context
  :args (s/cat :conn map? :req-id :ib/request-id)
  :ret (s/nilable map?))

(s/fdef ib.client/dropped-event-count
  :args (s/cat :conn map?)
  :ret int?)

(s/fdef ib.open-orders/open-orders-snapshot!
  :args (s/or :arity-1 (s/cat :conn :ib.conn/with-open-orders-guard)
              :arity-2 (s/cat :conn :ib.conn/with-open-orders-guard :opts :ib.config/open-orders-snapshot-opts))
  :ret :ib/channel)

(def public-api-vars
  "Vars to instrument in dev/test environments."
  [#'ib.client/connect!
   #'ib.client/disconnect!
   #'ib.client/events-chan
   #'ib.client/subscribe-events!
   #'ib.client/unsubscribe-events!
   #'ib.client/req-positions!
   #'ib.positions/positions-snapshot!
   #'ib.client/req-account-summary!
   #'ib.client/cancel-account-summary!
   #'ib.account/account-summary-snapshot!
   #'ib.client/req-open-orders!
   #'ib.client/req-all-open-orders!
   #'ib.client/req-account-updates!
   #'ib.client/cancel-account-updates!
   #'ib.client/register-request!
   #'ib.client/unregister-request!
   #'ib.client/request-context
   #'ib.client/dropped-event-count
   #'ib.open-orders/open-orders-snapshot!
   #'ib.client/connected?
   #'ib.client/req-market-data-type!
   #'ib.client/req-mkt-data!
   #'ib.client/cancel-mkt-data!
   #'ib.client/req-ids!
   #'ib.client/next-order-id!
   #'ib.client/place-order!
   #'ib.client/cancel-order!])
