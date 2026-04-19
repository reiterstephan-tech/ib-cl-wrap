# Events

This is the authoritative normalized event contract for `ib-cl-wrap`.

Status: Done for Phase 1 as of 2026-04-19.

## Common Envelope

Every normalized event includes:

- `:type`
- `:source`
- `:status`
- `:request-id`
- `:ts`
- `:schema-version`

`event-schema-v1.md` defines the versioned schema rules. New consumers should
use `:request-id` as the canonical request-correlation field.

Compatibility note:

- some event types still include legacy fields such as `:req-id` or `:id`
- those fields remain for `0.x` compatibility, but they are not canonical

## Stable Event Types

These event families are part of the stable contract in Phase 1.

- `:ib/connected`
  - Guaranteed keys: `:host`, `:port`, `:client-id`

- `:ib/disconnected`
  - Optional keys: `:reason`

- `:ib/error`
  - Optional keys: `:id`, `:code`, `:message`, `:raw`, `:request`, `:retryable?`

- `:ib/position`
  - Guaranteed keys: `:account`, `:contract`
  - Compatibility keys currently emitted: `:position`, `:avg-cost`

- `:ib/position-end`
  - No additional guaranteed payload keys

- `:ib/account-summary`
  - Guaranteed keys: `:request-id`, `:account`, `:tag`, `:value`, `:currency`
  - Compatibility key currently emitted: `:req-id`

- `:ib/account-summary-end`
  - Guaranteed keys: `:request-id`
  - Compatibility key currently emitted: `:req-id`

- `:ib/open-order`
  - Guaranteed keys: `:order-id`, `:contract`, `:order`, `:order-state`
  - Optional keys: `:perm-id`, `:account`

- `:ib/order-status`
  - Guaranteed keys: `:order-id`, `:status-text`
  - Optional keys: `:filled`, `:remaining`, `:avg-fill-price`, `:perm-id`,
    `:parent-id`, `:client-id`, `:last-fill-price`, `:why-held`,
    `:mkt-cap-price`

- `:ib/open-order-end`
  - No additional guaranteed payload keys

- `:ib/update-account-value`
  - Guaranteed keys: `:key`, `:value`, `:currency`, `:account`

- `:ib/update-account-time`
  - Guaranteed keys: `:time`

- `:ib/update-portfolio`
  - Guaranteed keys: `:contract`, `:position`, `:market-price`,
    `:market-value`, `:average-cost`, `:unrealized-pnl`, `:realized-pnl`,
    `:account`

- `:ib/account-download-end`
  - Guaranteed keys: `:account`

## Experimental Event Types

These event families are exposed, but remain experimental in Phase 1 because
their surrounding APIs are still expected to evolve in Phase 2.

- `:ib/next-valid-id`
  - Guaranteed keys: `:order-id`

- `:ib/reconnecting`
  - Guaranteed keys: `:attempt`, `:delay-ms`

- `:ib/reconnected`
  - Guaranteed keys: `:host`, `:port`, `:client-id`, `:attempt`

- `:ib/reconnect-failed`
  - Guaranteed keys: `:attempts`

- `:ib/tick-price`
  - Guaranteed keys: `:req-id`, `:field`
  - Optional keys: `:field-key`, `:price`

- `:ib/tick-snapshot-end`
  - Guaranteed keys: `:req-id`

- `:ib/contract-details`
  - Guaranteed keys: `:req-id`, `:contract-details`
  - One event per matching contract, followed by `:ib/contract-details-end`

- `:ib/contract-details-end`
  - Guaranteed keys: `:req-id`

## Contract Shape

The normalized inner contract map used in position, portfolio, and contract
details events has these guaranteed keys when available from IB:

- `:conId`
- `:symbol`
- `:secType`
- `:currency`
- `:exchange`
