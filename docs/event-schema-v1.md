# IB Event Schema v1

This document defines the normalized event contract used by `ib-cl-wrap`.

Status: Active and frozen for Phase 1 as of 2026-04-19.

## Required Top-Level Keys

Every emitted IB event map includes:

- `:type` keyword event type (for example `:ib/position`)
- `:source` event source marker (`:ib/tws` by default)
- `:status` one of `:ok` or `:error`
- `:request-id` integer request correlation id or `nil`
- `:ts` epoch milliseconds timestamp
- `:schema-version` string schema version (`"v1"`)

## Request Correlation

For request-scoped flows (for example account summary):

- `:request-id` carries the normalized request id.
- Legacy compatibility fields may still exist (`:req-id`, `:id`).
- `:ib/error` may include:
  - `:request-id`
  - `:request` (registered request context)
  - `:retryable?` (heuristic transient classification)

## Event Types in v1

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
- `:ib/reconnecting`
- `:ib/reconnected`
- `:ib/reconnect-failed`
- `:ib/tick-price`
- `:ib/tick-snapshot-end`
- `:ib/contract-details`
- `:ib/contract-details-end`

## Compatibility Notes

- Existing consumers using legacy keys continue to work.
- New consumers should prefer `:request-id` over legacy request-id keys.
- New optional keys may be added to existing event types without changing the
  schema version.
- Breaking meaning changes require a new schema version rather than a silent
  redefinition of `v1`.
