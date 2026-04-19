# Spec Surface

This is the authoritative public surface declaration for Phase 1.

Status: Done as of 2026-04-19.

Policy references:
- [Compatibility Policy](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/compatibility.md)
- [Event Contract](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/events.md)

## Stable Public API

These APIs are the frozen stable public surface for Phase 1.

### `ib.client`

- `connect!` - create a connection handle from a config map
- `disconnect!` - disconnect and close wrapper resources
- `events-chan` - return the shared event channel for diagnostics
- `subscribe-events!` / `unsubscribe-events!` - manage event subscriptions
- `req-positions!` - trigger `reqPositions`
- `req-open-orders!` - trigger `reqOpenOrders`
- `req-all-open-orders!` - trigger `reqAllOpenOrders`
- `req-account-summary!` / `cancel-account-summary!` - manage account-summary requests
- `req-account-updates!` / `cancel-account-updates!` - manage account-updates streaming
- `register-request!` / `unregister-request!` / `request-context` - request-correlation helpers
- `dropped-event-count` - diagnostic counter for events the wrapper could not enqueue

### `ib.positions`

- `positions-snapshot!` - snapshot helper built on the event stream

`positions-snapshot-from-events!` remains available as a collector helper for
tests and simulation, but it is not part of the stable surface.

### `ib.account`

- `account-summary-snapshot!` - snapshot helper for account summary requests

`account-summary-snapshot-from-events!` and `next-req-id!` remain available for
testing and advanced usage, but they are not part of the stable surface.

### `ib.open-orders`

- `open-orders-snapshot!` - snapshot helper for open orders

`open-orders-snapshot-from-events!` remains available as a collector helper,
but it is not part of the stable surface.

## Experimental Public API

These APIs are callable and documented, but they are still experimental in
Phase 1. They may be renamed, consolidated, or shape-normalized in Phase 2.

### `ib.client`

- `connected?`
- `req-market-data-type!`
- `req-mkt-data!`
- `cancel-mkt-data!`
- `req-contract-details!`
- `req-ids!`
- `next-order-id!`
- `place-order!`
- `cancel-order!`

### `ib.contract`

- `contract-details-snapshot!`

### `ib.market-data`

- `market-data-snapshot!`

## Internal Namespaces

The following namespaces contain important implementation helpers, but are not
stable public API contracts:

- `ib.events`
- `ib.errors`
- `ib.spec`

They may change in any `0.x` release unless a function is separately promoted
into the public surface.

## Stable Boundary Notes

- connection handles are opaque maps; consumers should not depend on their keys
- `:request-id` is the canonical request-correlation key for normalized events
- legacy event keys such as `:req-id` and `:id` remain compatibility fields
  during `0.x`, but are not canonical
- Phase 1 freezes which APIs are public; it does not freeze final canonical
  result envelopes for every helper
