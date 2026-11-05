# SCRUM-84 release evidence

Date: 2026-11-05 (Asia/Bangkok). Recorded by Codex.

## Neon migration

- Source migration: backend commit af2df07, immutable V11__seller_actual_photos_and_grading.sql. Existing V10 was also pending after the deployed V9 baseline.
- Read-only preflight found no PAID orders and no V11 column/table collisions. The database contained 52 listings.
- Before touching Neon, PostgreSQL 16.15 rehearsals covered fresh migration, repeat/no-op, upgrade, preservation of legacy listings and constraints. A separate negative test deliberately changed data inside the guard and proved both V10 and V11 rolled back together.
- Applied V10 and V11 in one Flyway group transaction through the configured direct Neon endpoint, with a five-second lock timeout and sixty-second statement timeout. A transactional callback took shared locks and compared aggregate sorted row fingerprints of all eleven existing application tables before/after each migration. The fingerprints exclude the five new nullable fields and normalize the documented PAID-to-CONFIRMED conversion; preflight found no PAID rows.
- Both migrations succeeded. Existing application data fingerprints matched. The new actual-photo table was empty: legacy covers were not relabeled as actual photographs and no grades were invented.
- Subsequent Flyway info reports V10 SUCCESS and V11 SUCCESS; validate succeeds. The packaged DatabaseMigrationApplication then performed Flyway plus JPA schema validation against Neon and exited zero without starting a web server or calling storage.
- No personal/order records were exported. Automatic approval review rejected an attempted full-data snapshot; that operation did not run. Verification used synthetic local data and in-database aggregate fingerprints instead.

## Application verification

- Backend: 266 total tests, zero failures/errors, two existing environment-dependent skips. The PostgreSQL checkout concurrency test was run separately and passed. A Windows packaging attempt encountered the owned smoke JVM's jar lock; stopping that JVM allowed packaging to succeed.
- Real HTTP API smoke: isolated local PostgreSQL plus configured real object storage, covering upload/public read/replacement/hidden edit/publication gate/deletion. Five unique objects were verified removed, along with disposable local records. This did not write to Neon.
- Frontend: full local unit suite 307/307; later review suite 8/8; lint and production build passed. Business Cypress finished 135 passing, zero failing and one existing opt-in live-staging pending case across 28 specs. API-mode design Cypress passed 12/12; a final fixture correction passed its unchanged four cases without duplicate-key warnings.

## CI and release ordering

Backend PR51 and frontend PR191 target main from feat/scrum-84-card-photo-policy. Both remain Draft and auto-merge is disabled. CI results are maintained in their descriptions so the evidence stays attached to the tested commits.

Initial Backend CI exposed an inherited order-dependent test cleanup: notification fixtures retained foreign keys to orders when collection suites ran later on Linux. Reproduction used explicit class order; the correction deletes dependent fixtures first and keeps all business assertions. The Docker build succeeded but the unchanged critical vulnerability gate rejected an inherited Alpine brotli-libs 1.2.0-r1 package. The runtime now pins the fixed 1.2.1-r0 package while retaining the digest-pinned Temurin base, non-root runtime and scanner gate. The official Alpine v3.24 CDN serves that exact package for amd64 and arm64. Refresh the base and remove this temporary override once the pinned base incorporates the fix.

Deployment order: Neon schema first (complete), then the Backend that enforces/uploads the new contract, then the Frontend that consumes it. Do not publish the new Frontend against the old Backend, which does not persist the new multipart parts. Coordinate the application releases to avoid leaving the old editor against the new publication policy.

No deployed application release or live-staging browser journey is claimed. The deployment runner was offline and backend GitHub Environments were not configured at preflight. These prerequisites and a reviewed release remain before deployment; no main branch merge or production rollout was performed.
