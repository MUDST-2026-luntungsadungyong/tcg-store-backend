# US-27-1 — Weekly stock and profit reports

## Product rules

- Applies to platform administrators only. No seller KYC documents or buyer details are exported.
- The Sunday 23:59 UTC job starts weekly processing. A reconciliation job runs every 60 seconds and finalizes after a one-minute grace period. UTC periods are **[previous Sunday 23:59, current Sunday 23:59)**, not calendar Monday–Sunday. The final minute belongs to the next period; no minute is dropped or counted twice.
- Complete periods before V31 are reconstructed from persisted FULFILLED order transitions and immutable purchase snapshots. These reports are labeled `RECONSTRUCTED_SALES_ONLY`; all stock fields are null/unavailable because historical stock snapshots do not exist. The first partial week around installation is skipped. Periods observed after V31 produce full tracked reports, including genuine zero-sales weeks.
- The stock and transaction fact tables preserve historical values. After downtime, the scheduler reconstructs missed tracked periods from these facts (up to 52 new reports per pass). Historical sales-only reports are created from the V31-imported order events. Existing reports are never recalculated or overwritten.
- Replicas serialize on `admin_report_state`. PostgreSQL fact-table locks wait for in-flight writes while generating a due report. Short-lived blocking is possible during report generation; failed transactions are retried on the next reconciliation.
- Reports and facts are retained without automatic deletion. No report edit/delete or manual generation endpoint is exposed.

## Metrics (THB)

| Metric | Definition |
| --- | --- |
| Stock added | Positive physical `on_hand` deltas: new listings and restocks. Installation baselines contribute zero. |
| Stock removed | Negative physical deltas: shipments, seller corrections and deletions. This is not the same as fulfilled sales. Reservations alone do not change physical stock. |
| Inventory units / retail value | Last observed physical stock per listing strictly before cut-off; `on_hand × price`. Includes hidden and reserved units. Deleted or zero-stock listings contribute zero. |
| Inventory cost | Same units at the recorded listing cost at cut-off. |
| Units sold / revenue | `FULFILLED` transition during the period, using immutable purchase quantity/price snapshots. Pending, confirmed, sent and cancelled orders are excluded from financial totals. |
| Gross profit | Fulfilled revenue minus purchase cost snapshots, **before platform fees**. Negative results remain negative. |
| Recorded platform fees | Sum of each fulfilled order's persisted `platform_fee`, once per order. It can legitimately be zero. It is not independent proof of money collected or settled by a payment provider; payment is outside the current platform. |
| Top cards | Two independent top-10 rankings: revenue descending and gross profit descending. Aggregate by global card ID; ties sort by card ID. |
| Stagnant inventory | Positive remaining stock on listings created **strictly more than 60 days** before cut-off and no SENT/FULFILLED activity in the last 60 days. Uses listing age, not FIFO lot age. Restocking does not create a new listing age. |

The PostgreSQL migration baselines existing stock and snapshots future stock writes and SENT/FULFILLED transitions in the same transaction. It imports old SENT/FULFILLED transition timestamps for historical sales reconstruction and stagnancy activity. Existing timestamp-without-time-zone data is interpreted as UTC, matching the API convention. If legacy data was stored as local wall time, normalize it before relying on historical periods or the 60-day boundary.

## API contract

All paths require a valid Bearer token with ADMIN role. Shared security returns 401 without authentication and 403 for non-admin users. Responses and exports use `Cache-Control: no-store`.

| Method / path | Parameters | Response |
| --- | --- | --- |
| GET `/api/admin/reports/weekly-summary/history` | `page=0`, `size=20` (1–100; page 0–100000) | `{content:[{weekEndDate,generatedAt,totalInventoryValue,totalFeesCollected,reportType}],number,size,totalElements,totalPages,trackingSince}`; newest first. A null inventory value means historical stock was not recorded. |
| GET `/api/admin/reports/weekly-summary` | optional `weekEnd=YYYY-MM-DD`, must be Sunday | Saved report, latest if omitted. Includes `reportType`, UTC `periodStart`, `periodEnd`, `generatedAt`, financial totals, and rankings. Tracked reports include all stock metrics; reconstructed sales-only reports return null for unavailable stock fields. |
| GET `/api/admin/reports/weekly-summary/export` | same `weekEnd` | Authenticated UTF-8 CSV with BOM and attachment filename. It includes report type and leaves unavailable historical stock cells blank. |

- 400 `INVALID_REPORT_REQUEST`: invalid Sunday/date or pagination (malformed parameter types also receive HTTP 400).
- 404 `WEEKLY_REPORT_NOT_FOUND`: the selected/latest report has not been generated. A missing report is not a zero-sales report.
- 5xx: database/API failure; frontend shows a retry state and does not substitute mock data.
- CSV quotes every cell, doubles embedded quotes and neutralizes user-controlled spreadsheet formulas. Numeric negative profits remain numeric.
- `CardPerformance`: `{cardId,name,unitsSold,revenue,grossProfit}`.
- `StagnantListing`: `{productId,sellerId,cardId,name,onHand,inventoryValue,listedAt}`. Listing rows are paginated in the UI at 20 per page; export contains all rows in the saved report.

## Running and storage

1. Apply normal PostgreSQL/Neon Flyway migrations through **V32** using the existing migration service/job before starting the new API version. This change does not apply migrations to an existing running database automatically.
2. `WEEKLY_REPORTS_ENABLED=true` enables scheduling (default). Migration and test profiles disable scheduling. Set false to pause generation; saved reports remain readable.
3. Open **Admin → Weekly reports** (`/admin/reports/weekly`). Select a saved period or page through history; use **Export CSV** to download it.
4. The reporting extension uses PostgreSQL transactional triggers. The existing TiDB Revision 5 schema does not contain these extension tables/triggers and requires its own migration/capture design before enabling this feature there.

Tables: `admin_report_state`, `admin_inventory_events`, `admin_order_events`, `admin_weekly_reports`. Facts intentionally have no foreign keys to mutable source records, so deleting a listing cannot erase historical reporting data. JSON snapshots use TEXT storage for explicit application serialization; numeric/date summary columns remain queryable. The extension is introduced in V31 and report classification in V32 after the existing V30 stock-notifications migration; previously released migrations are unchanged.

## Verification

```sh
./mvnw --batch-mode -Dtest=WeeklyReportServiceTests,AdminWeeklyReportControllerIntegrationTests test
# Use a disposable PostgreSQL database; tests create/drop only their own weekly_test_<UUID> schemas.
./mvnw --batch-mode -Dtest=WeeklyReportPostgresTests \
  -Dweekly.postgres.url=jdbc:postgresql://localhost:35437/weekly_report_test \
  -Dweekly.postgres.password=weekly-test-only test
```

Tests cover full/partial weeks, cut-off exclusion, real zero reports, financial snapshots, independent top-10 rankings, stagnant age/activity boundaries, unchanged saved history, CSV safety, authenticated routes, fresh PostgreSQL migrations, transactional triggers, rollback, deletion and simultaneous scheduler runs.

Frontend tests: `npm test -- src/features/admin/pages/AdminWeeklyReportsPage.test.tsx src/features/admin/api/weeklyReports.test.ts src/features/admin/layouts/AdminSidebar.test.tsx`. Cypress smoke: `cypress/e2e/admin/07_weekly_reports.cy.ts` (HTTP fixtures, not a live backend E2E test).

### PR branch verification — 2026-11-08

| Check | Result |
| --- | --- |
| Backend calculation / snapshot / CSV tests | 7 passed |
| Backend signed-JWT HTTP contract and authorization tests | 5 passed |
| PostgreSQL 16 fresh V1–V31 migrations, triggers, rollback, deletion, concurrent schedulers | 3 passed (original US-27 implementation) |
| Frontend report page, authenticated download client, admin navigation | 17 passed (single worker) |
| Cypress smoke in Chrome headless against production preview, HTTP fixtures | 4 passed |
| Frontend TypeScript / Vite production build | Passed; existing bundle-size/timing warnings |
| Lint for added report page, API client and their tests | Passed |

Backend used Java 21.0.9 and a disposable PostgreSQL container, not the running application database. The local JDK required an ASCII path alias and Mockito startup agent for tests; project dependency versions were unchanged. Frontend used Node 22.20.0 with dependencies installed from the existing lockfile. The 15 backend tests, 17 frontend tests, build and changed-file lint were rerun on branches based on the latest GitHub main. Cypress smoke passed on the original implementation; current PR-branch browser verification is recorded in the pull request. This verification covers the report feature; it is not a claim that the entire project regression suite was run.

## Migration compatibility with current main

The current canonical repository already uses V12 for auction listing management and V30 for stock notifications. Weekly reporting uses **V31** and historical report classification uses **V32**, preserving every previously released migration unchanged. Existing PostgreSQL/Neon databases should run the regular migration job through V32 before starting this API.

An earlier local prototype applied reporting under version V11 before the repository's seller-photo V11 was published. Such a prototype database requires a separately reviewed, backed-up Flyway-history reconciliation before adopting V31/V32; do not point this release at that database or edit applied migration checksums as a shortcut.
