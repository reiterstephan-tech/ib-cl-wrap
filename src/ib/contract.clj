(ns ib.contract
  "Contract details API built on top of the event stream.

  Use `contract-details-snapshot!` to resolve a partial contract description
  (symbol + secType + currency) into a list of fully-described contracts,
  each carrying a `conId` that can be used unambiguously in all subsequent
  API calls (market data, orders, etc.)."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.events :as events]))

(def default-timeout-ms
  "Default timeout for contract details snapshot requests."
  10000)

(def ^:private req-id-counter (atom 700000))

(defn- next-req-id! []
  (swap! req-id-counter inc))

(defn contract-details-snapshot-from-events!
  "Collect `:ib/contract-details` events for one `req-id` until
  `:ib/contract-details-end` or timeout.

  Returns a channel with one map:
  - success: `{:ok true :req-id ... :contracts [...]}`
  - error:   `{:ok false :error :timeout/:event-stream-closed/:ib-error ...}`"
  [events-ch {:keys [req-id timeout-ms]
               :or {timeout-ms default-timeout-ms}}]
  (let [out (async/chan 1)
        timeout-ch (async/timeout timeout-ms)]
    (async/go-loop [contracts []]
      (let [[value port] (async/alts! [events-ch timeout-ch])]
        (cond
          (= port timeout-ch)
          (do
            (async/>! out {:ok false
                           :error :timeout
                           :req-id req-id
                           :timeout-ms timeout-ms
                           :ts (events/now-ms)})
            (async/close! out))

          (nil? value)
          (do
            (async/>! out {:ok false
                           :error :event-stream-closed
                           :req-id req-id
                           :ts (events/now-ms)})
            (async/close! out))

          (and (= :ib/error (:type value))
               (= req-id (:request-id value)))
          (do
            (async/>! out {:ok false
                           :error :ib-error
                           :req-id req-id
                           :ib-error value
                           :ts (events/now-ms)})
            (async/close! out))

          (and (= :ib/contract-details (:type value))
               (= req-id (:req-id value)))
          (recur (conj contracts (:contract-details value)))

          (and (= :ib/contract-details-end (:type value))
               (= req-id (:req-id value)))
          (do
            (async/>! out {:ok true
                           :req-id req-id
                           :contracts contracts
                           :ts (events/now-ms)})
            (async/close! out))

          :else
          (recur contracts))))
    out))

(defn contract-details-snapshot!
  "Request contract details and return a channel with the snapshot result.

  Options:
  - `:symbol`           ticker symbol (e.g. `\"AAPL\"`)
  - `:con-id`           IB contract id (use instead of symbol when known)
  - `:sec-type`         default `\"STK\"`
  - `:exchange`         default `\"SMART\"`
  - `:currency`         default `\"USD\"`
  - `:req-id`           optional explicit request id
  - `:timeout-ms`       default 10000
  - `:tap-buffer-size`  default 256

  Returns a channel delivering one map:
  - success: `{:ok true :req-id ... :contracts [{:contract {:conId ...} :long-name ...} ...]}`
  - error:   `{:ok false :error :timeout/:event-stream-closed/:ib-error/:request-failed ...}`"
  ([conn contract-opts]
   (contract-details-snapshot! conn contract-opts {}))
  ([conn contract-opts {:keys [req-id timeout-ms tap-buffer-size]
                        :or {timeout-ms default-timeout-ms
                             tap-buffer-size 256}}]
   (let [rid (or req-id (next-req-id!))
         sub-ch (client/subscribe-events! conn {:buffer-size tap-buffer-size})
         collector-ch (contract-details-snapshot-from-events! sub-ch {:req-id rid
                                                                       :timeout-ms timeout-ms})
         out (async/chan 1)
         req-error (atom nil)]
     (try
       (client/req-contract-details! conn (assoc contract-opts :req-id rid))
       (catch Throwable t
         (reset! req-error t)))
     (if-let [t @req-error]
       (do
         (async/put! out {:ok false
                          :error :request-failed
                          :req-id rid
                          :message (.getMessage t)
                          :raw t
                          :ts (events/now-ms)})
         (client/unsubscribe-events! conn sub-ch)
         (async/close! sub-ch)
         (async/close! collector-ch)
         (async/close! out)
         out)
       (do
         (async/go
           (when-let [result (async/<! collector-ch)]
             (async/>! out result))
           (client/unsubscribe-events! conn sub-ch)
           (async/close! sub-ch)
           (async/close! out))
         out)))))
