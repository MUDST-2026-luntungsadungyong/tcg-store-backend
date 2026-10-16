# Step 1–2: real acceptance results — 2026-10-16

Source: `feat/kubernetes`, base revision `b859aa1`, followed by the Flyway integration fix in the working tree. This report records the initial failing run and the successful retest for Step 1–2. Kubernetes/provider-side acceptance is still separate.

Current status: **local Step 1 and Step 2 runtime gates pass with the fixed image**, without a Hibernate schema-creation workaround. Cloud S3 consistency/roundtrip and Neon snapshot restored into local PostgreSQL 18 also pass. A restore into a disposable Neon branch and Kubernetes/cloud replica tests remain pending.

## Work ownership

| Work item | Owner | Scope | Review gate |
|---|---|---|---|
| Local S3/API replicas | Luna 6 — Fermat, main review complete | `scripts/acceptance/step1-local*`, local evidence | Real S3 subcases passed; migration gate remains failed |
| Local PostgreSQL migration | Luna 6 — Nietzsche, main review complete | `scripts/acceptance/step2-local*`, local evidence | Four of five scenarios failed; Compose failure gate passed |
| Neon/object verification and integration | Main | Cloud acceptance scripts, this report and plan/status updates | Read-only DB inventory, real S3 roundtrip, snapshot backup/local restore, secret checks and evidence review |

The team uses the main checkout with disjoint file scopes. This testing round does not merge the PR or deploy Kubernetes.

## Targets and image identity

- Existing cloud API remains `tcg-store-service-backend-cloud` on port 8080, image ID `sha256:7b594f2ff8eb018b16fd41db327ab97e6e6b82bc16095ead57340d04aa512782`.
- Current-source isolated test image: `tcg-accept-20261016-backend:current`, local index `sha256:e4eaecd526e9a105d0a4c392c44820330a14872a3b65364d2a1cb736c3daf43f`, config `sha256:dc1fa32d96e3a0504cac4a3b55ac57197e212774f4a85dabc8a7e755af03630b`.
- Fixed retest image: `tcg-accept-20261016-backend:flyway-fixed`, local index `sha256:092994053b1ede5d54eafd5dfcadb2f8251e40f030dcf6d9803d68b2040bbb60`, config `sha256:5c91cde05a098ad54a26306a40567f3ee543c0068d8298738091d3a334d72b42`. Its revision label is `b859aa1-flyway-fix`, identifying a local working-tree build rather than a published registry release.
- Local PostgreSQL/S3/API tests use newly created task resources. The existing stopped local services/volumes are not used for destructive tests.
- Neon source is PostgreSQL 18.6. Snapshot restore target is a new local PostgreSQL 18 container, pinned image `postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873`.

## Confirmed cloud results

### S3 inventory and database references

Read-only Neon audit observed:

| Measurement | Observed |
|---|---:|
| Users | 15 |
| Catalog cards | 2,002 |
| Product listings | 22 |
| Orders / order items | 13 / 14 |
| Product image references | 22 |
| References under the configured cloud public base URL | 22 |
| References to localhost/S3Mock | 0 |
| Unique managed object keys | 22 |
| Objects in `tcg-product-images` | 22 |
| Total object bytes | 15,954,383 |
| Referenced objects missing from bucket | 0 |
| Unreferenced `products/` objects | 0 |
| Signed GETs checked against listed byte lengths | 22 / 22 |

**PASS — current cloud inventory/reference consistency.** The earlier statement that no cloud migration evidence exists is superseded by this audit. This run found the existing objects; it did not re-upload the existing product images or reconstruct the historical source-to-destination migration mapping. Byte length checks on existing images do not establish equality with historical source files.

The DB client used certificate/hostname verification and a read-only transaction. `clientTlsVerified=true`; Neon reports `pg_stat_ssl=false` for the provider-side internal session, which is recorded separately and does not describe the verified client connection.

### Actual cloud object roundtrip

**PASS — PUT → signed GET checksum → unauthenticated public GET checksum → DELETE → HEAD 404.** The test used one new UUID PNG object in the configured bucket and removed that exact object afterwards. Existing product objects and database rows were not changed. The subsequent inventory again contained 22 objects.

This proves the configured provider credentials/public URL work for direct S3 access. Cloud API upload/rollback and two cloud API replicas remain separate acceptance cases.

### Neon backup and local restore

**PASS — real Neon read-only consistent snapshot → `pg_dump` custom archive → isolated PostgreSQL 18 `pg_restore`.** The archive was 125,502 bytes, held in memory; the source snapshot remained open during `pg_dump` and comparison counts were taken from that snapshot. Sorted row hashes and counts matched on all eight selected tables:

`users`, `global_cards`, `local_cards`, `orders`, `order_items`, `cart_items`, `collection_items`, `flyway_schema_history`.

The original runner exited zero on the restored schema but did not establish Flyway execution. The **fixed-image retest** repeated the snapshot/restore and passed both Hibernate and explicit Flyway validation, with a successful Flyway validation message observed. The source history has one successful `BASELINE` record at version 9, rather than nine individual SQL migration records. Validation of that baseline does not reconstruct historical SQL checksums; fresh/upgrade fixtures verify actual SQL execution independently.

The dump scope was the application `public` schema. Provider roles, credentials, other schemas, S3 objects and provider configuration were not restored. The temporary local database/container/network were removed after comparison. No Neon database was created, reset or restored. Restore back into an isolated Neon branch remains pending.

### Secret checks

- **PASS:** exact known cloud JWT/password/S3 credential values were absent from tracked worktree files, captured running-cloud logs, and both existing/current image metadata/history.
- **PASS:** Gitleaks scanned the branch commit range with full redaction; 8 commits, approximately 870 KB, zero findings.
- **PASS:** Trivy 0.74.0 image filesystem secret scanner returned zero secret findings for the current-source test image. These scans cannot establish that unknown secrets never existed in external logs or systems.
- **PASS:** the fixed-image Trivy secret rescan also returned zero findings.

## Local evidence and release gates

Detailed evidence:

- [Step 1 local S3/API results](./evidence/step1-local-2026-10-16.md)
- [Step 2 local PostgreSQL results](./evidence/step2-local-2026-10-16.md)
- [Step 1 fixed-image retest](./evidence/step1-local-fixed-2026-10-16.md)
- [Step 2 fixed-image retest](./evidence/step2-local-fixed-2026-10-16.md)

Both helpers' files and evidence were reviewed by main. Fermat reached its usage limit after completing the real S3 run and writing evidence; main finalized the handoff and reporting. Both agents are closed.

| Test | Initial result before fix | Observed scope |
|---|---|---|
| S3 local two-instance upload/metadata visibility | PASS | A uploaded the exact PNG fixture; B returned the managed image URL from the shared DB |
| Shared reference deletion | PASS | First listing deletion kept the object; B's last-reference deletion removed it |
| Transaction rollback cleanup | PASS | A real PostgreSQL trigger rejected the insert after upload; the uploaded object was removed |
| Spoofed image rejection | PASS | Invalid bytes were rejected and object inventory did not change |
| Backend/S3Mock restart persistence | PASS | Both API containers and S3Mock restarted; readiness recovered and retained bytes matched |
| Fresh migration | FAIL | Runner exit 1, `missing table [audit_logs]`, no Flyway history, zero public tables |
| Seed/repeat migration | FAIL | Seed could not apply and repeat runner failed on the empty schema |
| Legacy V8 → V9 upgrade | FAIL | Legacy SQL fixture was created, but the runner did not establish Flyway history or normalize the legacy schema |
| Legacy fixture dump/restore plus runner validation | FAIL overall | The one-card fixture restored correctly, but runner validation failed on the unupgraded legacy column |
| Real Compose failure gate | PASS | Migration exited 1; dependent API was never started |
| Neon snapshot → local PG18 restore | PASS for selected data comparison | All eight selected tables matched counts/hashes; current existing schema passed Hibernate validation |

Local S3 subcases used Hibernate `update` to bootstrap the **disposable** schema after the real migration runner failed. Replica B used `validate`. This is a test fixture workaround, not a migration pass or a production configuration change.

### Migration blocker identified during main review

The original `pom.xml` included `flyway-core` and `flyway-database-postgresql`, but the original image contained no `spring-boot-flyway` module. Spring Boot 4 moved this integration into that module, supplied by [`spring-boot-starter-flyway` in the official 4.0.8 source](https://github.com/spring-projects/spring-boot/blob/v4.0.8/starter/spring-boot-starter-flyway/build.gradle). This explains why existing-schema Hibernate validation succeeded while a fresh schema was not initialized.

**Resolved in the follow-up:** replace the direct Flyway core dependency with `spring-boot-starter-flyway`, keep the PostgreSQL-specific module, and require the migration runner to obtain the Flyway bean and validate before returning success. Historical V1–V9 SQL files remain unchanged.

Luna 6 — Hubble updated the Step 1 harness; main reviewed it and ran the retests. Both API replicas now use `ddl-auto=validate` and start only after the migration runner and all nine history records are verified. Main also corrected the restore assertion to compare the source history count (a V8 baseline plus V9 has two records), added a real checksum-drift rejection case and pinned API/migration to the same expected image ID.

### Fixed-image retest results

| Case | Result | Evidence |
|---|---|---|
| Fresh PostgreSQL | PASS | V1–V9 history created; Hibernate validate succeeds |
| Repeat/seed | PASS | History remains 9; 2,000 seeded catalog cards and 10 users verified |
| Legacy V8 → V9 | PASS | Baseline V8 and SQL V9 recorded, listing preserved, legacy table names removed |
| Legacy backup/restore | PASS | Card count and source migration-history count preserved; restored runner validates |
| Existing-schema checksum drift | PASS | Changed recorded V9 checksum rejected by Flyway with nonzero exit |
| Compose migration failure | PASS | Failing migration blocks the dependent API from starting |
| S3 two-instance flow | PASS | Upload/read/shared delete/last delete/rollback/invalid bytes all pass after real migration |
| API and S3 restart persistence | PASS | Both validate-only API replicas and S3Mock restart; retained bytes match |
| Neon snapshot → local PG18 | PASS | Eight selected table counts/hashes match; fixed runner produces Flyway validation evidence |

The six real PostgreSQL scenarios pass. The full Maven suite also passes: **197 tests, 0 failures, 0 errors, 2 external-database skips**, including two new Boot 4 Flyway auto-configuration tests. The tests verify that enabled Flyway actually creates SQL migration history/data and that API mode can disable Flyway without creating migration tables.

The successful full-suite run used an isolated Maven output directory with the same source/dependencies, avoiding IDE-generated error-stub classes in the normal `target/classes` tree. Evidence: `target/flyway-fix-isolated-maven.log` and `target/flyway-isolated-verification/surefire-reports`. Earlier Windows/class-cache and container-fixture failures are superseded by that complete successful run; they were not counted as passes.

Main also checked PowerShell syntax, Python compilation, Compose configuration, documentation links and unchanged historical migration SQL. The fixed image secret scan found zero secrets. No live PostgreSQL concurrency or TiDB external test was inferred from the two skipped tests.

## Remaining work and next action

1. Run cloud API upload/rollback/reference deletion against dedicated accounts/data and exercise two cloud/Kubernetes replicas. Direct cloud S3 roundtrip and local replicas are already verified, but do not close this combined environment case.
2. Reconstruct historical image source inventory/mapping and compare source checksums if historical migration provenance is required; current bucket/DB reconciliation already passes.
3. Use a separately selected disposable Neon branch for provider-side fresh/upgrade/restore acceptance. The current run read Neon and restored only into local PostgreSQL 18.
4. Publish a committed fixed artifact through the trusted build flow before staging/production promotion. The existing cloud container remains on its previous image.

No Kubernetes Pod/rollout acceptance was run in this Step 1–2 round. The existing cloud API stayed healthy and the frontend stayed running. Task-created containers, networks and the S3 test volume were removed; synthetic data and the temporary restored copy are not retained. The isolated backend build and PostgreSQL 18 image remain available locally for reproduction.

## Reproduction

Install the test-only dependencies in the ignored tool directory:

```powershell
python -m venv target/acceptance-tools
target/acceptance-tools/Scripts/python.exe -m pip install -r scripts/acceptance/requirements.txt
```

Read-only cloud audit uses private configuration from the existing cloud container and emits only aggregate evidence:

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-readonly-audit.py --output target/acceptance-20261016/cloud-readonly.json
```

The following command writes and deletes one task-owned cloud object. It must only be used against the intended configured bucket:

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-object-roundtrip.py
```

Cloud backup reads the existing Neon app schema and restores into a newly created local container. It refuses container name collisions:

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-backup-restore.py --backend-image tcg-accept-20261016-backend:flyway-fixed --postgres-image postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873 --output target/acceptance-20261016/cloud-backup-restore-fixed.json
```

Safe aggregate JSON evidence and redacted scanner output are under ignored `target/acceptance-20261016/`. No raw database archive, credential, JWT or customer rows are placed in Git.
