# SCRUM-26 Email and Stock Notification Phase 2 Test Matrix

This matrix records the source inventory and commands actually run for the phase-2 email and seller stock notification work. The Cypress, SMTP, and coverage results below describe different test levels; coverage percentages are baselines, not quality gates.

## Current source inventory

| Suite | Source inventory after this phase | Observed run |
| --- | --- | --- |
| Frontend Vitest | 86 test files | `npm test -- --reporter=dot`: 86 files passed, 450 tests passed |
| Frontend Cypress | 45 spec files under `cypress/e2e`; full Cypress runner enumerated 206 cases, including 3 pending | Full run: 178 passed, 25 failed, 3 pending, 0 skipped |
| Backend Java tests | 67 Java test source files | `mvnw verify`: 449 tests, 0 failures, 0 errors, 19 skipped |
| Email SMTP boundary | `NotificationEmailDeliverySmtpIntegrationTest` plus the existing worker and service test classes | Focused worker/service/SMTP run: 19 passed, 0 failures/errors/skips |

The full Cypress source tree includes the new `20_seller_stock_notification_cta.cy.ts`. The browser runner reports dynamically registered cases; its run count is the reported execution inventory. The 3 pending cases are not counted as passes.

## Seller stock notification browser coverage

`cypress/e2e/20_seller_stock_notification_cta.cy.ts` uses the existing `cy.loginByMock('SELLER')` command, which seeds `token` and the persisted `tcg_auth_storage` user state with an approved seller. Each journey opens a real browser route from the notification inbox:

| Notification | Destination asserted | Guarded page evidence |
| --- | --- | --- |
| `SELLER_STOCK_CRITICAL` | `/workspace/stock-forecast?search=Charizard` | `Stockout Forecast` heading, retained search query, and successful seller forecast API response |
| `SELLER_OUT_OF_STOCK` | `/workspace/listings?search=Charizard` | `Listing Management` heading, retained search query, and successful seller listings API response |

Focused run: `npx cypress run --spec cypress/e2e/20_seller_stock_notification_cta.cy.ts` — 2 passed, 0 failed, 0 pending, 0 skipped. The full Cypress run also passed both cases.

The entire full Cypress run was attempted with `npm run test:e2e:full`: 45 specs, 206 cases, 178 passed, 25 failed, 3 pending, 0 skipped. Failures were limited to existing specs not changed in this phase:

| Existing failing spec | Failed cases | Observed issue |
| --- | ---: | --- |
| `03_cart_and_checkout_flow.cy.ts` | 1 | Cart stock-limit journey assertion failed |
| `10_collection_and_leaderboard.cy.ts` | 1 | Public collector showcase assertion failed |
| `11_seller_order_management.cy.ts` | 9 | Expected seller order rows/KPI and order actions were not present |
| `13_buyer_vouchers_and_checkout.cy.ts` | 2 | Guest profile-voucher and voucher checkout journeys failed |
| `17_actual_card_photos.cy.ts` | 9 | Listing catalog picker did not expose expected listbox/options |
| `19_voucher_admin_buyer_sync.cy.ts` | 1 | Existing order discount snapshot after voucher deletion assertion failed |
| `admin/04_seller_update_product.cy.ts` | 2 | Save control remained disabled in update-product journeys |

The affected specs and their production feature sources were unchanged from the supplied frontend starting commit (`22e81668`). The new seller journeys pass, but the 25 failures have not been reproduced against the base lockfile, so this report does not classify their root cause. They remain open full-suite gaps; no assertions were weakened.

## Backend email and SMTP coverage

`NotificationEmailDeliverySmtpIntegrationTest` starts GreenMail's embedded SMTP server on an ephemeral local port and configures a real Spring `JavaMailSenderImpl` to deliver only to that server. It receives the worker's MIME email and checks the subject, sender and recipient, nested plain-text/HTML alternatives, critical-stock CTA URL, and decryption of a sensitive body before delivery. The outbox and order repositories remain mocks, so this is an SMTP/MIME boundary integration test rather than a database-backed outbox test. No external mail provider is contacted.

Focused command:

```powershell
.\mvnw.cmd '-Dtest=NotificationEmailDeliveryWorkerTest,NotificationEmailServiceTest,NotificationEmailDeliverySmtpIntegrationTest' test
```

Result: 19 tests, 0 failures, 0 errors, 0 skipped. Existing focused class breakdown: `NotificationEmailDeliveryWorkerTest` 10, `NotificationEmailServiceTest` 8, SMTP boundary test 1.

Full backend command: `.\mvnw.cmd verify` — BUILD SUCCESS; 67 source files, 449 tests, 0 failures, 0 errors, 19 skipped. The verify run exercises the current backend test suite; it does not imply SMTP-provider behavior beyond the embedded-server test above.

## Coverage baseline

Frontend coverage is enabled through Vitest's V8 provider without thresholds. Command: `npm run test:coverage`. Result: 86 files and 450 tests passed; statements 51.69% (4,175/8,076), branches 50.06% (3,708/7,407), functions 47.84% (1,146/2,395), and lines 54.22% (3,687/6,800). HTML report: `tcg-store-frontend/coverage/index.html`; text summary is printed by the command.

Backend JaCoCo runs during `verify`, with no coverage thresholds. The observed baseline analyzed 306 classes: instructions 82.4% (29,526/35,835), branches 64.1% (1,991/3,106), lines 81.3% (5,281/6,499), and methods 82.4% (1,009/1,224). HTML report: `tcg-store-backend/target/site/jacoco/index.html`; CSV: `tcg-store-backend/target/site/jacoco/jacoco.csv`; execution data: `tcg-store-backend/target/jacoco.exec`.

Frontend production build command `npm run build` passed. Vite emitted a large-chunk advisory; no build errors occurred.

## Remaining gaps

- The 25 failures across the 7 existing Cypress specs listed above need base-branch reproduction and separate investigation; the focused seller notification journeys pass.
- SMTP testing uses an embedded local server and mocked outbox/order repositories. Provider-specific behavior, database-backed outbox retries/expiry, and production-provider delivery are not covered.
- Coverage is an initial reporting baseline. No project-wide thresholds or high-coverage gates were introduced.
