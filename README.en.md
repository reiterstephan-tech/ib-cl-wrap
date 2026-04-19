# ib-cl-wrap

Project documentation:
- [Changelog](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/CHANGELOG.md)
- [API Stabilization Roadmap](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/roadmap.md)
- [Downstream Migration Guide](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/downstream-migration.md)
- [Compatibility Policy](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/compatibility.md)

Asynchronous Clojure wrapper for the Interactive Brokers TWS/IB Gateway Java API.

The wrapper uses `core.async` with an event-driven model and avoids blocking logic in IB callback threads.

## Language

- German README: [README.md](README.md)
- `README.md` is the authoritative top-level README for policy and project positioning.
- `README.en.md` is the maintained English translation.

## Spec-driven

The project is spec-driven:
- central specs: [spec.clj](src/ib/spec.clj)
- public surface overview: [spec-surface.md](docs/spec-surface.md)
- event contract: [events.md](docs/events.md)

Guaranteed:
- structured config maps for public APIs
- structured event maps per `:type`
- structured snapshot result maps

## Setup

1. Place `ibapi.jar` at `lib/ibapi.jar`.
2. Start TWS or IB Gateway.
3. Enable API access and open the socket port.

Typical ports:
- TWS Paper: `7497`
- TWS Live: `7496`
- IB Gateway Paper: `4002`
- IB Gateway Live: `4001`

`deps.edn` already includes `lib/ibapi.jar` in `:paths`. If the JAR is missing, `ib.client/connect!` and other IB-facing functions throw a descriptive exception.

## API Status

Phase 1 is complete.

Stable for downstream use:
- `ib.client` connection, event subscription, positions, account-summary,
  open-orders, and request-correlation APIs
- `ib.positions/positions-snapshot!`
- `ib.account/account-summary-snapshot!`
- `ib.open-orders/open-orders-snapshot!`

Experimental during `0.x`:
- market data
- contract details
- reconnect-oriented event families
- order-id and order-placement APIs

Handles returned by `connect!` are opaque maps. Downstream code should not rely
on internal keys.

## API

### Namespace `ib.client`

- `connect!` - connect to TWS/Gateway and start the `EReader` loop (no busy-wait).
- `disconnect!` - disconnect and close event resources.
- `subscribe-events!` - create subscriber channel for events.
- `unsubscribe-events!` - remove subscriber channel.
- `req-positions!` - trigger `reqPositions()`.
- `req-account-summary!` - trigger `reqAccountSummary(reqId, group, tags)`.
- `cancel-account-summary!` - trigger `cancelAccountSummary(reqId)`.
- `req-account-updates!` - trigger `reqAccountUpdates(true, account)` for Account Window streaming.
- `cancel-account-updates!` - trigger `reqAccountUpdates(false, account)` to stop the stream.
- `req-open-orders!` - trigger `reqOpenOrders()` (open orders for the current client ID; with clientId 0, manual TWS orders can be bound).
- `req-all-open-orders!` - trigger `reqAllOpenOrders()` (all open API orders regardless of client ID).
- `register-request!` / `unregister-request!` / `request-context` - request-correlation helpers.
- `dropped-event-count` - number of events not enqueued.
- `events-chan` - return shared event channel.
- `connected?`, `req-market-data-type!`, `req-mkt-data!`, `cancel-mkt-data!`, `req-contract-details!`, `req-ids!`, `next-order-id!`, `place-order!`, `cancel-order!` - available, but still experimental in Phase 1.

### Namespace `ib.positions`

- `positions-snapshot!` - trigger `reqPositions()`, collect `:ib/position` until `:ib/position-end`, return snapshot via channel.
- `positions-snapshot-from-events!` - collector helper for simulated tests.

### Namespace `ib.account`

- `account-summary-snapshot!` - trigger `reqAccountSummary`, collect `:ib/account-summary` until `:ib/account-summary-end` (for one `req-id`), always cancels subscription.
- `account-summary-snapshot-from-events!` - collector helper for simulated tests.
- `next-req-id!` - request-id allocator.

### Namespace `ib.open-orders`

- `open-orders-snapshot!` - one-shot open orders snapshot with `:mode :open` (`reqOpenOrders`) or `:mode :all` (`reqAllOpenOrders`).
- `open-orders-snapshot-from-events!` - collector helper for simulated tests.
- in-flight guard prevents parallel open-orders snapshots on one connection (`:snapshot-in-flight` error).

### Namespace `ib.contract`

- `contract-details-snapshot!` - available, but still experimental in Phase 1.

### Namespace `ib.market-data`

- `market-data-snapshot!` - available, but still experimental in Phase 1.

Default balance tags:
- `NetLiquidation`
- `TotalCashValue`
- `AvailableFunds`
- `BuyingPower`
- `UnrealizedPnL`
- `RealizedPnL`

### Namespace `ib.events`

`ib.events` remains an important internal normalization namespace, but it is
not part of the stable public API contract.

## Event Schema

Minimum event types:
- `{:type :ib/connected ...}`
- `{:type :ib/error ...}`
- `{:type :ib/position ...}`
- `{:type :ib/position-end ...}`
- `{:type :ib/account-summary ...}`
- `{:type :ib/account-summary-end ...}`
- `{:type :ib/open-order ...}`
- `{:type :ib/order-status ...}`
- `{:type :ib/open-order-end ...}`
- `{:type :ib/update-account-value ...}`
- `{:type :ib/update-account-time ...}`
- `{:type :ib/update-portfolio ...}`
- `{:type :ib/account-download-end ...}`
- `{:type :ib/reconnecting ...}` (experimental)
- `{:type :ib/reconnected ...}` (experimental)
- `{:type :ib/reconnect-failed ...}` (experimental)
- `{:type :ib/tick-price ...}` (experimental)
- `{:type :ib/tick-snapshot-end ...}` (experimental)
- `{:type :ib/contract-details ...}` (experimental)
- `{:type :ib/contract-details-end ...}` (experimental)

Unified v1 envelope keys:
- `:type`
- `:source`
- `:status`
- `:request-id`
- `:ts`
- `:schema-version`

For correlated `:ib/error` events:
- `:request-id`
- `:request`
- `:retryable?`

New downstream integrations should treat `:request-id` as the canonical
request-correlation key. Legacy keys such as `:req-id` remain compatibility
fields during `0.x`.

Versioned event contract: [event-schema-v1.md](docs/event-schema-v1.md)

Account Summary is subscription-based in IB. The snapshot helper actively cancels via `cancelAccountSummary` on success and timeout/error.

Account Updates (`reqAccountUpdates`) is also subscription-based and mirrors TWS Account Window data.

## Overflow Strategy

Default is bounded `:sliding` channels.

When buffers are full, older events are dropped and newer events are kept. Callback threads do not block.

## Example

```clojure
(require '[clojure.core.async :as async]
         '[ib.client :as ib]
         '[ib.account :as acct]
         '[ib.open-orders :as oo]
         '[ib.positions :as pos])

(def conn
  (ib/connect! {:host "127.0.0.1"
                :port 7497
                :client-id 42
                :event-buffer-size 2048
                :overflow-strategy :sliding}))

(def events-ch (ib/subscribe-events! conn {:buffer-size 512}))

(async/go-loop []
  (when-let [evt (async/<! events-ch)]
    (println "EVENT" evt)
    (recur)))

(def positions-ch (pos/positions-snapshot! conn {:timeout-ms 5000}))
(def balances-ch (acct/account-summary-snapshot! conn {:group "All" :timeout-ms 5000}))
(def open-orders-ch (oo/open-orders-snapshot! conn {:mode :open :timeout-ms 5000}))

(async/go
  (let [result (async/<! positions-ch)]
    (println "Positions:" result)))

(async/go
  (let [result (async/<! balances-ch)]
    (println "Balances:" result)))

(async/go
  (let [result (async/<! open-orders-ch)]
    (println "Open Orders:" result)))

(ib/req-account-updates! conn {:account "DU123456"})
;; ... later
(ib/cancel-account-updates! conn "DU123456")
(ib/unsubscribe-events! conn events-ch)
(ib/disconnect! conn)
```

## Tests

Tests run without a live IB connection (simulated events):

```bash
clj -M:test
```

The test profile enables `clojure.spec.test.alpha/instrument` for public API functions.
Instrumentation is not automatically enabled in production.

Covered:
- contract normalization
- snapshot collector logic
- timeout behavior
- account-summary event normalization and timeout/cancel behavior
- account-updates API and event normalization
- open-orders snapshot collector (timeout + in-flight guard)
- spec instrumentation and generative spec checks

## Paper vs Live

- Paper and Live use different ports/accounts.
- Use distinct `client-id` per client connection.
- Validate in Paper first, then enable Live.
