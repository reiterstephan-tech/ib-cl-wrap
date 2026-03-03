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
  - Keys: `:order-id`

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
