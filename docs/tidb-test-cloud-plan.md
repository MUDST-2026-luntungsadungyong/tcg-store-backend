# TiDB Cloud Starter test environment

## Decision

Use a TiDB Cloud **Starter** instance in AWS Singapore (`ap-southeast-1`) for the shared test database. Keep PostgreSQL as the local/default profile until the TiDB validation suite passes.

TiDB speaks the MySQL protocol, not PostgreSQL. Moving this application therefore requires a MySQL JDBC driver, a TiDB-specific Spring profile, and a MySQL/TiDB schema. Changing only `DB_URL` is not sufficient.

## Cost guardrail

- Create one Starter instance with monthly spending limit `0`.
- The first five Starter instances in an organization each include 5 GiB row storage, 5 GiB columnar storage, and 50 million Request Units per month.
- At the free quota, new connections can be denied and existing connections throttled until the next monthly reset. Do not treat this tier as production availability.
- Start with a Hikari pool of 5 connections per backend instance. Keep Cloud Run/container max instances low enough that total possible connections remain bounded.

## Files prepared in this repository

- `.env.tidb.example`: required environment variables, without secrets.
- `src/main/resources/application-tidb.yml`: MySQL driver, TiDB Hibernate dialect, TLS connection, UTC, and a small pool.
- `infra/tidb/init/01-schema.sql`: TiDB-compatible schema matching the current JPA entities.
- `infra/tidb/init/02-test-data.sql`: two test accounts and three sample listings.

## One-time console setup

1. In TiDB Cloud, create a Starter instance named `mythix-tcg-test`.
2. Choose AWS and Singapore (`ap-southeast-1`).
3. Set the monthly spending limit to `0`.
4. Open **Connect**, select Public/General, and generate a database password.
5. Save the host, port, prefixed username, password, and database name in the deployment secret store. Never commit them.
6. In SQL Editor, run `infra/tidb/init/01-schema.sql`, then `infra/tidb/init/02-test-data.sql`.

Creating/resetting the database password is intentionally a user action because it creates persistent credentials.

## Runtime variables

| Variable | Required value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `tidb` |
| `TIDB_JDBC_URL` | JDBC URL copied from TiDB Cloud, using `sslMode=VERIFY_IDENTITY` and TLS 1.2/1.3 |
| `TIDB_USER` | Prefixed username shown by TiDB Cloud, for example `abc123.root` |
| `TIDB_PASSWORD` | Generated password stored as a secret |
| `APP_JWT_SECRET` | Random value of at least 32 bytes, stored as a secret |
| `APP_CORS_ALLOWED_ORIGINS` | Exact frontend test URL |
| `APP_PUBLIC_BASE_URL` | Exact backend test URL |
| `TIDB_POOL_MAX_SIZE` | Start at `5` |
| `TIDB_POOL_MIN_IDLE` | Start at `0` |

The Starter plan requires TLS. Java can normally verify the Let's Encrypt certificate through its default trust store.

## Validation and migration order

1. Run the schema and seed scripts in a new TiDB database.
2. Start the backend with the `tidb` profile and confirm Hibernate `validate` passes.
3. Run auth, browse, seller inventory, cart, checkout concurrency, collection, and order-history integration tests against TiDB.
4. Verify pessimistic row locking under concurrent checkout; the current code relies on `PESSIMISTIC_WRITE`.
5. Export real PostgreSQL data to normalized CSV and import in parent-to-child order. For a small test data set, CSV is simpler than AWS DMS.
6. Compare row counts and money totals per table before switching the shared test backend.
7. Keep rollback as a configuration change back to the PostgreSQL profile until the TiDB soak test is complete.

## Known blockers before cutover

- `infra/postgres/init/01-schema.sql` on current `main` uses `global_cards/local_cards`, while the running JPA model uses `cards/products`. H2 tests do not catch this because Hibernate creates the H2 schema. Fix and add a real-database schema-validation job.
- PostgreSQL seed/migrations contain `BIGSERIAL`, `JSONB`, `setval`, `::regclass`, and PostgreSQL interval syntax. They cannot be executed on TiDB.
- TiDB Cloud migration tooling focuses on MySQL-compatible sources. For PostgreSQL, the official guidance points to AWS DMS; for this small test database, a controlled CSV export/import is lower complexity.
- Foreign keys are supported in current TiDB, but cascading behavior and checkout concurrency must be tested under the actual Starter version.

## Acceptance checklist

- [ ] Starter instance exists in Singapore with spending limit 0.
- [ ] Credentials exist only in the deployment secret store.
- [ ] Schema and seed scripts complete without warnings that change semantics.
- [ ] Backend starts with `SPRING_PROFILES_ACTIVE=tidb` and `/api/health` returns 200.
- [ ] Full TiDB integration suite passes, including concurrent checkout.
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
