# TiDB Cloud Starter test environment

## Decision

Use a TiDB Cloud **Starter** instance in AWS Singapore (`ap-southeast-1`) for the shared test database. Keep PostgreSQL as the local/default profile until the TiDB validation suite passes.

TiDB speaks the MySQL protocol, not PostgreSQL. Moving this application therefore requires a MySQL JDBC driver, a TiDB-specific Spring profile, and a MySQL/TiDB schema. Changing only `DB_URL` is not sufficient.

## Cost guardrail

- Create one Starter instance with monthly spending limit `0`.
- The first five Starter instances in an organization each include 5 GiB row storage, 5 GiB columnar storage, and 50 million Request Units per month.
- At the free quota, new connections can be denied and existing connections throttled until the next monthly reset. Do not treat this tier as production availability.
- Start with a Hikari pool of 5 connections per backend instance. Keep Cloud Run/container max instances low enough that total possible connections remain bounded.

## Provisioned test resource

- Instance: `mythix-tcg-test` (`10023880819699183600`)
- Status: Active
- Plan: Starter, spending currently `Free`, monthly spending limit `0`
- Provider/region: AWS Singapore (`ap-southeast-1`)
- TiDB version: `v8.5.3`, MySQL-compatible, zonal availability
- Public endpoint: `gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000`
- Username: prefixed TiDB username stored in the deployment secret store
- Runtime database: `tcg_store_revision5`
- Migration source database: `tcg_store_test` (read-only source for the copy)
- SQL Editor verification: 2 users, 1 seller profile, 3 cards, and 3 products

## Files prepared in this repository

- `.env.tidb.example`: required environment variables, without secrets.
- `src/main/resources/application-tidb.yml`: MySQL driver, TiDB Hibernate dialect, TLS connection, UTC, and a small pool.
- `infra/tidb/init/01-schema.sql`: legacy TiDB source schema for `tcg_store_test` only.
- `infra/tidb/init/02-test-data.sql`: source fixtures used to exercise the copy.
- `infra/tidb/migrations/001_revision5_schema.sql`: TiDB-compatible Revision 5 DDL
  derived from `tcg-store-document/docs/DATABASE_DESIGN.md`.
- `infra/tidb/migrations/002_revision5_data.sql`: idempotent copy from the legacy
  `tcg_store_test` fixtures into `tcg_store_revision5`; the source database is not
  modified.
- `docs/tidb-revision5-schema-reconciliation.md`: source-of-truth comparison,
  mapping decisions, and TiDB verification evidence.

## One-time console setup

1. In TiDB Cloud, create a Starter instance named `mythix-tcg-test`.
2. Choose AWS and Singapore (`ap-southeast-1`).
3. Set the monthly spending limit to `0`.
4. Open **Connect**, select Public/General, and generate a database password.
5. Save the host, port, prefixed username, password, and database name in the deployment secret store. Never commit them.
6. If the migration source is empty, run the legacy init scripts in
   `tcg_store_test`, then run `001_revision5_schema.sql` and
   `002_revision5_data.sql`; the application runtime uses only
   `tcg_store_revision5` after cutover.

Creating/resetting the database password is intentionally a user action because it creates persistent credentials.

## Runtime variables

| Variable | Required value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `tidb` |
| `TIDB_JDBC_URL` | JDBC URL copied from TiDB Cloud, selecting `tcg_store_revision5` and using `sslMode=VERIFY_IDENTITY` with TLS 1.2/1.3 |
| `TIDB_USER` | Prefixed username shown by TiDB Cloud, for example `abc123.root` |
| `TIDB_PASSWORD` | Generated password stored as a secret |
| `APP_JWT_SECRET` | Random value of at least 32 bytes, stored as a secret |
| `APP_CORS_ALLOWED_ORIGINS` | Exact frontend test URL |
| `APP_PUBLIC_BASE_URL` | Exact backend test URL |
| `TIDB_POOL_MAX_SIZE` | Start at `5` |
| `TIDB_POOL_MIN_IDLE` | Start at `0` |

The Starter plan requires TLS. Java can normally verify the Let's Encrypt certificate through its default trust store.

## Validation and migration order

1. Run the Revision 5 schema and data migrations in `tcg_store_revision5`.
2. Run the repository Revision 5 contract test and confirm the Java mappings use
   `global_cards`/`local_cards` and the renamed foreign keys.
3. Start the backend with the `tidb` profile and confirm Hibernate `validate` passes.
4. Run auth, browse, seller inventory, cart, checkout concurrency, collection, and order-history integration tests against TiDB.
5. Verify pessimistic row locking under concurrent checkout; the current code relies on `PESSIMISTIC_WRITE`.
6. Export real PostgreSQL data to normalized CSV and import in parent-to-child order. For a small test data set, CSV is simpler than AWS DMS.
7. Compare row counts and money totals per table before switching the shared test backend.
8. Keep rollback as a configuration change back to the PostgreSQL profile until the TiDB soak test is complete.

### Revision 5 reconciliation

The database design document defines ten tables using `global_cards` and
`local_cards`, plus KYC, audit-log, financial-order, and order-timeline fields.
The original TiDB test schema created the legacy `cards` and `products` model
used by the current backend. The reconciliation is therefore an expand migration:

1. Run `001_revision5_schema.sql` in TiDB.
2. Run `002_revision5_data.sql` in TiDB.
3. Compare row counts and key totals between `tcg_store_test` and
   `tcg_store_revision5`.
4. Keep the legacy database available only as the migration source and rollback
   reference until the application entities and runtime profile are cut over.

Current migration verification: all ten target tables exist, mapped row counts
match the legacy fixtures, order totals reconcile to `0.00`, and all checked
foreign-key orphan counts are `0`.

The migration intentionally does not drop or rename objects in `tcg_store_test`.
The application runtime must not use that legacy database after cutover. Rollback
is a configuration change back to the PostgreSQL profile; removal of
`tcg_store_revision5` requires a separate, explicitly approved destructive action.

## Known blockers before cutover

- The Java entity/service cutover must compile and pass the Revision 5 contract
  test before the live TiDB workflow becomes a required deployment gate. H2
  tests alone cannot validate the real TiDB schema because Hibernate creates
  the H2 schema with `create-drop`.
- PostgreSQL seed/migrations contain `BIGSERIAL`, `JSONB`, `setval`, `::regclass`, and PostgreSQL interval syntax. They cannot be executed on TiDB.
- TiDB Cloud migration tooling focuses on MySQL-compatible sources. For PostgreSQL, the official guidance points to AWS DMS; for this small test database, a controlled CSV export/import is lower complexity.
- Foreign keys are supported in current TiDB, but cascading behavior and checkout concurrency must be tested under the actual Starter version.

## Acceptance checklist

- [x] Starter instance exists in Singapore with spending limit 0.
- [ ] Credentials exist only in the deployment secret store.
- [x] Schema and seed scripts complete without warnings that change semantics.
- [ ] Backend starts with `SPRING_PROFILES_ACTIVE=tidb` against `tcg_store_revision5` and `/api/health` returns 200.
- [ ] Full TiDB integration suite passes, including concurrent checkout.
- [x] Static Revision 5 migration/JPA contract validation is present in CI.
- [ ] Manual live TiDB Revision 5 validation workflow passes.
- [ ] Frontend test environment uses the TiDB-backed API and its exact origin is in CORS.
- [ ] Row-count and monetary-total reconciliation passes.
- [ ] A rollback to PostgreSQL has been rehearsed.

## Official references

- TiDB Cloud Starter FAQ: https://docs.pingcap.com/tidbcloud/serverless-faqs/
- Create a Starter instance: https://docs.pingcap.com/tidbcloud/create-tidb-cluster-serverless/
- Spring Boot connection guide: https://docs.pingcap.com/developer/dev-guide-sample-application-java-spring-boot/
- TLS requirements: https://docs.pingcap.com/tidbcloud/secure-connections-to-serverless-clusters/
- MySQL compatibility: https://docs.pingcap.com/tidbcloud/mysql-compatibility/
- Migration overview: https://docs.pingcap.com/tidbcloud/tidb-cloud-migration-overview/
