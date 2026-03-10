(ns ib.market-data
  "Market data snapshot API built on top of the event stream.

  Uses market data type 4 (delayed-frozen) by default so no paid
  market data subscription is required for testing."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.events :as events]))

(def ^:private req-id-counter (atom 800000))

(defn- next-req-id! []
  (swap! req-id-counter inc))

(defn market-data-snapshot!
  "Request a single market data snapshot for one contract.

  Sets market data type to 4 (delayed-frozen) before the request so
  it works without a live data subscription.

  Options:
  - `:con-id`      IB contract id (preferred; use instead of symbol when known)
  - `:sec-type`    default \"STK\"
  - `:exchange`    default \"SMART\"
  - `:primary-exch` primary exchange for SMART routing (e.g. \"NYSE\")
  - `:currency`    default \"USD\"
  - `:timeout-ms`  default 8000

  Returns a channel delivering one map:
  - success: `{:ok true  :symbol ... :ticks {:bid ... :ask ... :last ...}}`
  - error:   `{:ok false :error :timeout/:ib-error/:stream-closed ... }`"
  ([conn symbol]
   (market-data-snapshot! conn symbol {}))
  ([conn symbol {:keys [con-id sec-type exchange primary-exch currency timeout-ms]
                 :or {sec-type "STK" exchange "SMART" currency "USD" timeout-ms 8000}}]
   (let [rid        (next-req-id!)
         sub-ch     (client/subscribe-events! conn {:buffer-size 64})
         out        (async/chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (try
       (client/req-market-data-type! conn 4)
       (catch Throwable _ nil))
     (let [req-err (try
                     (client/req-mkt-data! conn {:req-id       rid
                                                 :symbol       symbol
                                                 :con-id       con-id
                                                 :sec-type     sec-type
                                                 :exchange     exchange
                                                 :primary-exch primary-exch
                                                 :currency     currency
                                                 :snapshot     true})
                     nil
                     (catch Throwable t t))]
       (if req-err
         (do
           (async/put! out {:ok false :error :request-failed
                            :symbol symbol :message (.getMessage req-err)})
           (async/close! out)
           (client/unsubscribe-events! conn sub-ch)
           (async/close! sub-ch))
         (async/go-loop [ticks {}]
           (let [[val port] (async/alts! [sub-ch timeout-ch])
                 done-result
                 (cond
                   (= port timeout-ch)
                   (do (client/cancel-mkt-data! conn rid)
                       (if (seq ticks)
                         {:ok true  :symbol symbol :ticks ticks}
                         {:ok false :error :timeout :symbol symbol
                          :message "No tick data received before timeout"}))

                   (nil? val)
                   {:ok false :error :stream-closed :symbol symbol}

                   (and (= :ib/tick-snapshot-end (:type val))
                        (= rid (:req-id val)))
                   (do (client/cancel-mkt-data! conn rid)
                       {:ok true :symbol symbol :ticks ticks})

                   (and (= :ib/error (:type val))
                        (= rid (:request-id val)))
                   (do (client/cancel-mkt-data! conn rid)
                       {:ok false :error :ib-error :symbol symbol
                        :message (:message val) :code (:code val)})

                   :else nil)]
             (if done-result
               (do
                 (async/>! out done-result)
                 (async/close! out)
                 (client/unsubscribe-events! conn sub-ch)
                 (async/close! sub-ch))
               (let [new-ticks
                     (if (and (= :ib/tick-price (:type val))
                              (= rid (:req-id val))
                              (:field-key val))
                       (assoc ticks (:field-key val) (:price val))
                       ticks)]
                 (recur new-ticks)))))))
     out)))
