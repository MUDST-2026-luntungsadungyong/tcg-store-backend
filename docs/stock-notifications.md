# Stock notifications

## Buyer back-in-stock subscriptions

All routes require an authenticated user. `globalCardId` is the string ID from `global_cards.id`.

| Method | Route | Result |
| --- | --- | --- |
| GET | `/api/stock-subscriptions/{globalCardId}` | `200 { globalCardId, subscribed, subscribedAt }`; an absent subscription returns `subscribed: false` and `subscribedAt: null` |
| PUT | `/api/stock-subscriptions/{globalCardId}` | Creates or returns the existing subscription; repeated requests are idempotent |
| DELETE | `/api/stock-subscriptions/{globalCardId}` | `204`, including when no subscription exists |

PUT returns `409 { code: "STOCK_ALREADY_AVAILABLE", message, fieldErrors: null }` when any `SHOW` listing has positive available stock. Once one or more such listings exist, the polling worker creates one `STOCK_AVAILABLE` notification per subscriber and removes the subscription in the same transaction. The notification action path is `/cards/{encodedGlobalCardId}`. Email uses the existing notification outbox and is sent only when mail delivery is enabled.

## Seller forecast alerts

The worker evaluates visible (`SHOW`) listings with the existing `StockForecastService` defaults: 30 day weighted sales, 7 day lead time, 3 safety days, and 30 day target cover. Hidden and `NO_RECENT_SALES` listings do not alert. Severity transitions create at most one notification per level before a recovery/reset:

| Forecast level | Notification type | Email | Action path |
| --- | --- | --- | --- |
| LOW | `SELLER_STOCK_LOW` | No | `/workspace/stock-forecast?search={encodedName}` |
| CRITICAL | `SELLER_STOCK_CRITICAL` | If seller alert email and mail delivery are enabled | `/workspace/stock-forecast?search={encodedName}` |
| OUT_OF_STOCK | `SELLER_OUT_OF_STOCK` | If seller alert email and mail delivery are enabled | `/workspace/listings?search={encodedName}` |

SAFE, NO_RECENT_SALES, and HIDE reset the stored alert cycle. The worker holds a database mutex row lock while notifications, outbox rows, subscriptions, and alert state are committed, serializing processing across pods. Database effects are exactly once per transition; SMTP delivery remains at-least-once because a process can fail after the provider accepts a message and before the outbox records success.

## Feature flags

All default to `false`:

- `APP_BACK_IN_STOCK_NOTIFICATION_ENABLED`
- `APP_SELLER_STOCK_ALERT_ENABLED`
- `APP_SELLER_STOCK_ALERT_EMAIL_ENABLED`

Email transport still requires the existing `APP_MAIL_ENABLED` setting and sender configuration.
