# Step 2 local PostgreSQL acceptance — 2026-10-16

Run window: 2026-10-16 10:34:20Z to 2026-10-16 10:39:09Z
Target: isolated PostgreSQL 16 in tmpfs on a task-prefixed Docker network; no application or database volume was reused.
Runner: current-source backend image, invoked with DatabaseMigrationApplication / PropertiesLauncher. Runtime output is suppressed.

| Scenario | Result | Observed evidence |
| --- | --- | --- |
| Isolated PostgreSQL startup | PASS | PostgreSQL 16 container ready; tmpfs storage; no named or pre-existing volume. |
| Fresh database and Hibernate validation | PASS | Flyway V1–V9 recorded successfully; migration application exited zero. |
| Seed separation and repeat migration | PASS | Repeat retained 9 migrations; seeded row checks passed (cards=2000, users=10). |
| Legacy V8 baseline adoption and V9 upgrade | PASS | V8 baseline and V9 succeeded; the legacy listing count was preserved and old table names were removed. |
| pg_dump / pg_restore and restored database validation | PASS | Custom-format dump restored; global_cards count matched (1); restored-target migration runner exited zero. |
| Checksum drift is rejected on an existing schema | PASS | Runner exited nonzero with Flyway validation failure on checksum drift; unchanged schema alone cannot pass the release gate. |
| Compose migration failure blocks dependent backend API container | PASS | Compose returned nonzero (1); migration state=exited:1; dependent API was never started. |
| Result | PASS | All 6 local database acceptance scenarios passed. |
| Cleanup isolated container | PASS | Removed task-owned disposable PostgreSQL container and anonymous storage, if any. |
| Cleanup isolated network | PASS | Removed task-owned Docker network. |
| Cleanup Compose gate stack | PASS | Removed only resources belonging to the task-prefixed failure-gate project. |

No cloud database, existing container, or pre-existing volume was used. No credentials, connection strings, or raw runner logs are retained.
