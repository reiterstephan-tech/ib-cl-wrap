(ns ib.account
  "Account summary (balances) API built on top of the event stream.

  IB account summary is a subscription. `account-summary-snapshot!` cancels the
  subscription on every completion path (success, timeout, IB error)."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.events :as events]))

(def default-timeout-ms
  "Default timeout for account summary snapshot requests."
  5000)

(def default-group
  "Default IB account summary group."
  client/default-account-summary-group)

(def default-tags
  "Default account summary tags focused on balances."
  client/default-account-summary-tags)

(def ^:private req-id-counter
  (atom 900000))

(defn next-req-id!
  "Generate next request id for account summary snapshots."
  []
  (swap! req-id-counter inc))

(defn- add-summary-value [values {:keys [account tag value currency]}]
  (assoc-in values [account tag] {:value value
                                  :currency currency}))

(defn account-summary-snapshot-from-events!
  "Collect `:ib/account-summary` events for one `req-id` until
  `:ib/account-summary-end` or timeout.

  Returns a channel with one map:
  - success: `{:ok true :req-id ... :values {...}}`
  - error: `{:ok false :error ...}`"
  [events-ch {:keys [req-id timeout-ms]
              :or {timeout-ms default-timeout-ms}}]
  (let [out (async/chan 1)
        timeout-ch (async/timeout timeout-ms)]
    (async/go-loop [values {}]
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
               (= req-id (:id value)))
          (do
            (async/>! out {:ok false
                           :error :ib-error
                           :req-id req-id
                           :ib-error value
                           :ts (events/now-ms)})
            (async/close! out))

          (and (= :ib/account-summary (:type value))
               (= req-id (:req-id value)))
          (recur (add-summary-value values value))

          (and (= :ib/account-summary-end (:type value))
               (= req-id (:req-id value)))
          (do
            (async/>! out {:ok true
                           :req-id req-id
                           :values values
                           :ts (events/now-ms)})
            (async/close! out))

          :else
          (recur values))))
    out))

(defn account-summary-snapshot!
  "Request an account summary snapshot.

  Options:
  - `:group` default `All`
  - `:tags` default focused balance tags
  - `:req-id` optional explicit request id
  - `:timeout-ms` default 5000
  - `:tap-buffer-size` default 256

  Returns channel delivering one result map.
  Subscription is always cancelled (success/error/timeout)."
  ([conn]
   (account-summary-snapshot! conn {}))
  ([conn {:keys [group tags req-id timeout-ms tap-buffer-size]
          :or {group default-group
               tags default-tags
               timeout-ms default-timeout-ms
               tap-buffer-size 256}}]
   (let [rid (or req-id (next-req-id!))
         sub-ch (client/subscribe-events! conn {:buffer-size tap-buffer-size})
         collector-ch (account-summary-snapshot-from-events! sub-ch {:req-id rid
                                                                     :timeout-ms timeout-ms})
         out (async/chan 1)
         req-error (atom nil)]
     (try
       (client/req-account-summary! conn {:req-id rid
                                          :group group
                                          :tags tags})
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
         (try
           (client/cancel-account-summary! conn rid)
           (catch Throwable _ nil))
         (client/unsubscribe-events! conn sub-ch)
         (async/close! sub-ch)
         (async/close! collector-ch)
         (async/close! out)
         out)
       (do
         (async/go
           (when-let [result (async/<! collector-ch)]
             (async/>! out result))
           (try
             (client/cancel-account-summary! conn rid)
             (catch Throwable _ nil))
           (client/unsubscribe-events! conn sub-ch)
           (async/close! sub-ch)
           (async/close! out))
         out)))))
