# Events

All events include envelope keys:
- `:type`
- `:source`
- `:status`
- `:request-id`
- `:ts`
- `:schema-version`

## Event Types

- `:ib/connected`
  - Keys: `:host`, `:port`, `:client-id`

- `:ib/disconnected`
  - Optional keys: `:reason`

- `:ib/error`
  - Optional keys: `:id`, `:code`, `:message`, `:raw`, `:request`, `:retryable?`

- `:ib/next-valid-id`
  - Keys: `:order-id` (seeds the internal counter used by `next-order-id!` and `place-order!`)

- `:ib/position`
  - Keys: `:account`, `:contract`, `:position`, `:avg-cost`

- `:ib/position-end`
  - No additional payload keys.

- `:ib/account-summary`
  - Keys: `:req-id`, `:account`, `:tag`, `:value`, `:currency`

- `:ib/account-summary-end`
  - Keys: `:req-id`

- `:ib/open-order`
  - Keys: `:order-id`, `:perm-id`, `:account`, `:contract`, `:order`, `:order-state`

- `:ib/order-status`
  - Keys: `:order-id`, `:status-text`, `:filled`, `:remaining`, `:avg-fill-price`, `:perm-id`, `:parent-id`, `:client-id`, `:last-fill-price`, `:why-held`, `:mkt-cap-price`

- `:ib/open-order-end`
  - No additional payload keys.

- `:ib/update-account-value`
  - Keys: `:key`, `:value`, `:currency`, `:account`

- `:ib/update-account-time`
  - Keys: `:time`

- `:ib/update-portfolio`
  - Keys: `:contract`, `:position`, `:market-price`, `:market-value`, `:average-cost`, `:unrealized-pnl`, `:realized-pnl`, `:account`

- `:ib/account-download-end`
  - Keys: `:account`

- `:ib/reconnecting`
  - Keys: `:attempt`, `:delay-ms`

- `:ib/reconnected`
  - Keys: `:host`, `:port`, `:client-id`, `:attempt`

- `:ib/reconnect-failed`
  - Keys: `:attempts`

- `:ib/tick-price`
  - Keys: `:req-id`, `:field` (raw IB tick type integer), `:field-key` (`:bid`/`:ask`/`:last`/`:high`/`:low`/`:close`/`:open` or `nil`), `:price`

- `:ib/tick-snapshot-end`
  - Keys: `:req-id`

- `:ib/contract-details`
  - Keys: `:req-id`, `:contract-details` (map with `:contract` (see contract shape below), `:long-name`, `:min-tick`, `:trading-hours`, `:liquid-hours`, `:time-zone-id`)
  - One event per matching contract; followed by `:ib/contract-details-end`
  - The inner `:contract` map has keys: `:conId`, `:symbol`, `:secType`, `:currency`, `:exchange`

- `:ib/contract-details-end`
  - Keys: `:req-id`
