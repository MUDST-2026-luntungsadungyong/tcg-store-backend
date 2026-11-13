# SCRUM-26 Email and Stock Notification Phase 2 Test Matrix

This report records the focused validation for Phase 2. Counts below describe the current source tree after these changes; they do not imply that every Cypress spec was run.

## Current test inventory

| Test level | Current inventory | Phase 2 run |
| --- | --- | --- |
| Frontend Cypress E2E | 44 spec files, 188 declared `it(...)` cases under `cypress/e2e` | 2 focused specs, 29/29 passed |
| Backend Java tests | 66 unit, integration, concurrency, and contract test source files | 2 focused classes, 18/18 passed |
| Frontend unit tests | Includes `NotificationsPage.test.tsx` and stock subscription API/Card Detail tests | Not rerun; existing test coverage was audited |
| Email integration / SMTP tests | No real SMTP or live provider coverage | Not run; delivery uses mocked `JavaMailSender` in focused worker tests |

“E2E case” means one Cypress `it(...)` declaration. The inventory count is source based; it is not a claim that all 188 cases pass. The focused Cypress run covered `05_notifications_inbox.cy.ts` (15/15) and `12_card_detail_multi_seller_and_recommendations.cy.ts` (14/14). The latter includes the new authenticated buyer subscribe/unsubscribe flow on an out-of-stock card. Notification endpoints are intercepted; no external SMTP is involved.

## Backend module coverage

| Module / test class | Focused cases | What the tests establish |
| --- | ---: | --- |
| `NotificationEmailDeliveryWorkerTest` | 10 | `EmailContentRenderer` injection and use; multipart plain-text plus HTML alternatives; sensitive-body decryption at delivery; PDF attachment snapshot and size rules; dynamic-content escaping and HTTP(S)-only links; stock available, critical, and out-of-stock category/accent/CTA; unknown subjects do not select a stock template. |
| `NotificationEmailServiceTest` | 8 | Email type eligibility; stock-available and seller critical/out-of-stock email selection; `SELLER_STOCK_LOW` remains web-only; seller critical email feature gate; sensitive-body encryption and expiry behavior; enqueue/defer behavior. |
| Persistence / delivery integration | 0 run | No live mail server, SMTP retry integration, database-backed outbox, or expiry/attachment persistence integration was exercised in Phase 2. |

The two focused backend classes ran with:

```powershell
.\mvnw.cmd '-Dtest=NotificationEmailDeliveryWorkerTest,NotificationEmailServiceTest' test
```

Result: 18 tests, 0 failures, 0 errors.

## Frontend stock coverage and remaining gaps

| Module / test | Coverage |
| --- | --- |
| `CardDetailPage` Cypress flow | Authenticated buyer subscribes then unsubscribes on an out-of-stock Card Detail page; subscription state and success feedback are asserted. |
| `NotificationsPage.test.tsx` route matrix | Memory-router navigation cases cover `STOCK_AVAILABLE`, `SELLER_STOCK_CRITICAL`, and `SELLER_OUT_OF_STOCK` CTA destinations, including mark-read-before-navigation. |
| `05_notifications_inbox.cy.ts` | Browser route test covers `STOCK_AVAILABLE` to Card Detail. |
| Seller CTA browser flows | Critical stock forecast and listing management CTA browser journeys remain unverified. The tested browser session is a buyer, and this app guards those destinations as seller-only; attempts to seed seller auth through this spec did not load a valid UI session. The corresponding destinations remain covered by the component-level route matrix. |
| Live backend subscription / email | Not exercised; Cypress uses mock HTTP responses and no external SMTP. |
