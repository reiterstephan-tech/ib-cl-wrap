# Spec Surface

## Public Surface

### `ib.client`
- `connect!` - connection config map (`:host`, `:port`, `:client-id`, `:event-buffer-size`, `:overflow-strategy`) -> connection handle map.
- `disconnect!` - connection handle -> disconnected handle.
- `events-chan` - connection handle -> shared event channel.
- `subscribe-events!` / `unsubscribe-events!` - event subscription lifecycle.
- `req-positions!` - trigger IB `reqPositions`.
- `req-open-orders!` - trigger IB `reqOpenOrders`.
- `req-all-open-orders!` - trigger IB `reqAllOpenOrders`.
- `req-account-summary!` / `cancel-account-summary!` - account summary request lifecycle.
- `req-account-updates!` / `cancel-account-updates!` - account updates streaming lifecycle.
- `register-request!` / `unregister-request!` / `request-context` - request correlation registry helpers.

### `ib.positions`
- `positions-snapshot!` - returns a channel yielding one snapshot result (vector of position events or error map).
- `positions-snapshot-from-events!` - collector helper for simulated event streams.

### `ib.account`
- `account-summary-snapshot!` - returns a channel yielding one result map (`{:ok true ...}` / `{:ok false ...}`).
- `account-summary-snapshot-from-events!` - collector helper for simulated event streams.
- `next-req-id!` - request id allocator.

### `ib.open-orders`
- `open-orders-snapshot!` - returns a channel yielding one result map (`{:ok true :orders [...]}` / `{:ok false ...}`).
- `open-orders-snapshot-from-events!` - collector helper for simulated event streams.

## Event Contract

Current event types emitted by the wrapper:
- `:ib/connected`
- `:ib/disconnected`
- `:ib/error`
- `:ib/next-valid-id`
- `:ib/position`
- `:ib/position-end`
- `:ib/account-summary`
- `:ib/account-summary-end`
- `:ib/open-order`
- `:ib/order-status`
- `:ib/open-order-end`
- `:ib/update-account-value`
- `:ib/update-account-time`
- `:ib/update-portfolio`
- `:ib/account-download-end`

All events follow the v1 unified envelope keys:
- `:type`
- `:source`
- `:status`
- `:request-id`
- `:ts`
- `:schema-version`
