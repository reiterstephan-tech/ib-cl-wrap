(ns ib.open-orders
  "Open orders API built on top of IB openOrder/openOrderEnd callbacks.

  `reqOpenOrders` and `reqAllOpenOrders` do not carry a request id. To avoid
  ambiguous event correlation, only one open-orders snapshot can be in flight
  per connection."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.events :as events]))

(def default-timeout-ms
  "Default timeout for open orders snapshot requests."
  5000)

(defn- begin-open-orders-snapshot! [{:keys [open-orders-snapshot-in-flight]}]
  (when-not open-orders-snapshot-in-flight
    (throw (ex-info "Connection map missing :open-orders-snapshot-in-flight" {})))
  (compare-and-set! open-orders-snapshot-in-flight false true))

(defn- end-open-orders-snapshot! [{:keys [open-orders-snapshot-in-flight]}]
  (when open-orders-snapshot-in-flight
    (reset! open-orders-snapshot-in-flight false))
  true)

(defn open-orders-snapshot-from-events!
  "Collect all `:ib/open-order` events until `:ib/open-order-end` or timeout.

  Returns a channel with one result map:
  - success: `{:ok true :orders [...]}`
  - timeout: `{:ok false :error :timeout}`"
  ([events-ch]
   (open-orders-snapshot-from-events! events-ch {}))
  ([events-ch {:keys [timeout-ms]
               :or {timeout-ms default-timeout-ms}}]
   (let [out (async/chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (async/go-loop [orders []]
       (let [[value port] (async/alts! [events-ch timeout-ch])]
         (cond
           (= port timeout-ch)
           (do
             (async/>! out {:ok false
                            :error :timeout
                            :timeout-ms timeout-ms
                            :ts (events/now-ms)})
             (async/close! out))

           (nil? value)
           (do
             (async/>! out {:ok false
                            :error :event-stream-closed
                            :ts (events/now-ms)})
             (async/close! out))

           (= :ib/open-order (:type value))
           (recur (conj orders value))

           (= :ib/open-order-end (:type value))
           (do
             (async/>! out {:ok true
                            :orders orders
                            :ts (events/now-ms)})
             (async/close! out))

           :else
           (recur orders))))
     out)))

(defn open-orders-snapshot!
  "Request a one-shot open orders snapshot.

  Options:
  - `:mode` one of `:open` (reqOpenOrders) or `:all` (reqAllOpenOrders)
  - `:timeout-ms` default 5000
  - `:tap-buffer-size` default 256

  Returns channel with one result map.
  If another snapshot is in flight, returns `{:ok false :error :snapshot-in-flight}`."
  ([conn]
   (open-orders-snapshot! conn {}))
  ([conn {:keys [mode timeout-ms tap-buffer-size]
          :or {mode :open
               timeout-ms default-timeout-ms
               tap-buffer-size 256}}]
   (let [out (async/chan 1)]
     (if-not (begin-open-orders-snapshot! conn)
       (do
         (async/put! out {:ok false
                          :error :snapshot-in-flight
                          :ts (events/now-ms)})
         (async/close! out)
         out)
       (let [sub-ch (client/subscribe-events! conn {:buffer-size tap-buffer-size})
             collector-ch (open-orders-snapshot-from-events! sub-ch {:timeout-ms timeout-ms})
             req-error (atom nil)]
         (try
           (case mode
             :open (client/req-open-orders! conn)
             :all (client/req-all-open-orders! conn)
             (throw (ex-info "Invalid open orders snapshot mode" {:mode mode})))
           (catch Throwable t
             (reset! req-error t)))
         (if-let [t @req-error]
           (do
             (async/put! out {:ok false
                              :error :request-failed
                              :mode mode
                              :message (.getMessage t)
                              :raw t
                              :ts (events/now-ms)})
             (client/unsubscribe-events! conn sub-ch)
             (async/close! sub-ch)
             (async/close! collector-ch)
             (end-open-orders-snapshot! conn)
             (async/close! out)
             out)
           (do
             (async/go
               (when-let [result (async/<! collector-ch)]
                 (async/>! out result))
               (client/unsubscribe-events! conn sub-ch)
               (async/close! sub-ch)
               (end-open-orders-snapshot! conn)
               (async/close! out))
             out)))))))
