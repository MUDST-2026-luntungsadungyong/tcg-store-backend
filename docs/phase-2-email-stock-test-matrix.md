# SCRUM-26 Email and Stock Notification Phase 2 Test Matrix

This matrix records the source inventory and commands actually run for the phase-2 email and seller stock notification work. The Cypress, SMTP, and coverage results below describe different test levels; coverage percentages are baselines, not quality gates.

## Current source inventory

| Suite | Source inventory after this phase | Observed run |
| --- | --- | --- |
| Frontend Vitest | 86 test files | `npm test -- --reporter=dot`: 86 files passed, 450 tests passed |
| Frontend Cypress | 45 spec files under `cypress/e2e`; full Cypress runner enumerated 206 cases, including 3 pending | CI-equivalent full run: 203 passed, 0 failed, 3 pending, 0 skipped |
| Backend Java tests | 67 Java test source files | `mvnw verify`: 449 tests, 0 failures, 0 errors, 19 skipped |
| Email SMTP boundary | `NotificationEmailDeliverySmtpIntegrationTest` plus the existing worker and service test classes | Focused worker/service/SMTP run: 19 passed, 0 failures/errors/skips |

The full Cypress source tree includes the new `20_seller_stock_notification_cta.cy.ts`. The browser runner reports dynamically registered cases; its run count is the reported execution inventory. The 3 pending cases are not counted as passes.

## Seller stock notification browser coverage

`cypress/e2e/20_seller_stock_notification_cta.cy.ts` uses the existing `cy.loginByMock('SELLER')` command, which seeds `token` and the persisted `tcg_auth_storage` user state with an approved seller. Each journey opens a real browser route from the notification inbox:

| Notification | Destination asserted | Guarded page evidence |
| --- | --- | --- |
| `SELLER_STOCK_CRITICAL` | `/workspace/stock-forecast?search=Charizard` | `Stockout Forecast` heading, retained search query, alert copy, and rendered urgency summary |
| `SELLER_OUT_OF_STOCK` | `/workspace/listings?search=Charizard` | `Listing Management` heading and retained search query |

The CI business-test server enables `VITE_ENABLE_MOCK_API=true`, so these pages resolve listing and forecast data from the in-browser mock database without issuing seller HTTP requests. This E2E spec therefore verifies the user-visible CTA, guarded navigation, query propagation, and rendered destination state rather than the API transport boundary. The listing and forecast adapters have separate unit coverage.

Focused mock-mode run: `npx cypress run --spec cypress/e2e/20_seller_stock_notification_cta.cy.ts` — 2 passed, 0 failed, 0 pending, 0 skipped. The earlier full local Cypress run also passed both cases.

The earlier `npm run test:e2e:full` command was only an alias for raw `cypress run`. It incorrectly ran the business browser-mock specs and the design HTTP-fixture specs against one default HTTP-mode server. That invalid environment produced 25 misleading failures:

| Historically failing spec | Failed cases | Observed issue in the invalid single-server run |
| --- | ---: | --- |
| `03_cart_and_checkout_flow.cy.ts` | 1 | Cart stock-limit journey assertion failed |
| `10_collection_and_leaderboard.cy.ts` | 1 | Public collector showcase assertion failed |
| `11_seller_order_management.cy.ts` | 9 | Expected seller order rows/KPI and order actions were not present |
| `13_buyer_vouchers_and_checkout.cy.ts` | 2 | Guest profile-voucher and voucher checkout journeys failed |
| `17_actual_card_photos.cy.ts` | 9 | Listing catalog picker did not expose expected listbox/options |
| `19_voucher_admin_buyer_sync.cy.ts` | 1 | Existing order discount snapshot after voucher deletion assertion failed |
| `admin/04_seller_update_product.cy.ts` | 2 | Save control remained disabled in update-product journeys |

The failures were reproduced from latest `main` with its base lockfile and then rerun using the same two partitions as CI: business specs on port 3000 with `VITE_ENABLE_MOCK_API=true`, followed by design specs on port 3001 with the mock API disabled. All seven historically failing specs passed in their matching mode. The corrected full runner completed 38 business specs with 182 passed and 3 opt-in live tests pending, then 7 design specs with 21 passed: 45 specs, 206 cases, 203 passed, 0 failed, 3 pending, 0 skipped. This classifies the historical failures as test-environment mismatch rather than product regressions.

The frontend `npm run test:e2e:full` command now owns both Vite servers, waits for readiness, runs those two partitions, and cleans up the child processes. The GitHub Actions E2E job calls the same command, so local and CI full-regression orchestration share one implementation.

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

- SMTP testing uses an embedded local server and mocked outbox/order repositories. Provider-specific behavior, database-backed outbox retries/expiry, and production-provider delivery are not covered.
- Coverage is an initial reporting baseline. No project-wide thresholds or high-coverage gates were introduced.
