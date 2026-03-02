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

(defn now-ms
  "Return current timestamp in epoch milliseconds."
  []
  (System/currentTimeMillis))

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

(defn position->event
  "Build normalized `:ib/position` event from IB callback payload."
  [{:keys [account contract position avg-cost]}]
  {:type :ib/position
   :ts (now-ms)
   :account account
   :contract (contract->map contract)
   :position (parse-double-safe position)
   :avg-cost (parse-double-safe avg-cost)})

(defn account-summary->event
  "Build normalized `:ib/account-summary` event from IB callback payload."
  [{:keys [req-id account tag value currency]}]
  {:type :ib/account-summary
   :ts (now-ms)
   :req-id (parse-long-safe req-id)
   :account (some-> account str)
   :tag (some-> tag str)
   :value (some-> value str)
   :currency (some-> currency str)})

(defn error->event
  "Build normalized `:ib/error` event from IB callback payload."
  [{:keys [id code message raw]}]
  {:type :ib/error
   :ts (now-ms)
   :id id
   :code code
   :message (some-> message str/trim)
   :raw raw})
