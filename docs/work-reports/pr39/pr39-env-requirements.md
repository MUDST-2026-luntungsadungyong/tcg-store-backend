# Docker Desktop environment requirements

> Superseded: the supplied cloud settings are now present, with no required
> runtime fields missing. See [current verified status](pr39-desktop-cloud-ready.md).
> Below is the historical source audit before configuration was supplied.

Read-only audit of `backend/docker-compose.desktop.yml` and `backend/.env.example`. No `.env` contents were inspected; `backend/.env` does not exist. The current compose file names the project `tcg-store-service` and is therefore not isolated from a fresh environment.

## Profile inputs

| Scope | Variable | Compose behavior |
|---|---|---|
| Both profiles | `APP_JWT_SECRET` | Required; Compose fails if unset. |
| Both profiles | `APP_CORS_ALLOWED_ORIGINS` | Defaults to localhost ports 3000, 5173, 5174 and 127.0.0.1 equivalents. |
| Both profiles | `APP_JWT_EXPIRATION_MINUTES` | Defaults to `1440`. |
| Both profiles | `APP_LOG_LEVEL` | Defaults to `INFO`. |
| Both profiles | `JPA_SHOW_SQL` | Defaults to `false`. |
| Local only | `POSTGRES_DB` | Defaults to `tcg_store`. |
| Local only | `POSTGRES_USER` | Defaults to `postgres`. |
| Local only | `POSTGRES_PASSWORD` | Defaults to `postgres`; local-development default. |
| Local only | `POSTGRES_PORT` | Defaults to host port `5432`, bound to loopback. |
| Local only | `S3MOCK_API_PORT` | Defaults to host port `9090`, bound to loopback. Local object-store endpoint, region, bucket, path style and credentials are set directly in Compose; no cloud object-store variables are needed. |
| Cloud only | `NEON_JDBC_URL` | Required by migration and backend services. |
| Cloud only | `NEON_DB_USERNAME` | Required by migration and backend services. |
| Cloud only | `NEON_DB_PASSWORD` | Required by migration and backend services. |
| Cloud only | `NEON_POOL_MAX_SIZE` / `NEON_POOL_MIN_IDLE` | Defaults to `5` / `0`. |
| Cloud only | `OBJECT_STORAGE_BUCKET` | Required. |
| Cloud only | `OBJECT_STORAGE_PUBLIC_BASE_URL` | Required. |
| Cloud only | `OBJECT_STORAGE_ACCESS_KEY` / `OBJECT_STORAGE_SECRET_KEY` | Required. Values must come from local protected configuration and must not be included in reports. |
| Cloud only | `OBJECT_STORAGE_ENDPOINT` | Defaults to the empty string in Compose and `.env.example`. Confirm whether that is valid for the intended Neon S3-compatible endpoint before using the cloud profile. |
| Cloud only | `OBJECT_STORAGE_REGION` / `OBJECT_STORAGE_PATH_STYLE` | Defaults to `us-east-2` / `true`. |
| Both profiles | `SERVER_PORT` | Defaults to host port `8080` for the selected backend. |
| Both profiles | `VITE_API_BASE_URL` | Defaults to `http://localhost:8080/api` at frontend build time. |
| Both profiles | `FRONTEND_PORT` | Defaults to host port `3000`. |
| Build/image | `BACKEND_IMAGE_NAME` / `BACKEND_IMAGE_TAG` | Defaults to `tcg-store-service-backend:dev`. |
| Build metadata | `SOURCE_DATE_EPOCH`, `OCI_CREATED`, `OCI_REVISION`, `OCI_VERSION` | Have defaults in Compose; these do not need `.env` entries for a basic local build. |

For the intended local Desktop profile, the only mandatory Compose variable is `APP_JWT_SECRET`; the local database and S3Mock use local defaults. Cloud-profile values are separate and should not be required to build or start the isolated local profile.

## `.env.example` scope

`.env.example` exists and describes both direct Spring runs and cloud development, but it is not a minimal Compose profile file. These entries are not read by `docker-compose.desktop.yml`: `DATABASE_URL`, `DATABASE_URL_UNPOOLED`, `NEON_BRANCH`, `SPRING_PROFILES_ACTIVE`, `FLYWAY_BASELINE_ON_MIGRATE`, and all `TIDB_*` entries. `FLYWAY_ENABLED` is set explicitly by Compose for API/migration services. `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` from the example are also superseded in the local profile by values derived from `POSTGRES_*`.

Compose variables missing from `.env.example` include `BACKEND_IMAGE_NAME`, `BACKEND_IMAGE_TAG`, `SOURCE_DATE_EPOCH`, `OCI_CREATED`, `OCI_REVISION`, `OCI_VERSION`, and `S3MOCK_API_PORT`; all have Compose defaults. The current `.env` is absent, so the required `APP_JWT_SECRET` is not supplied by a project `.env` file. This audit checked presence only and did not inspect shell secret values.

## Fixed names and isolation constraints

The file pins project name `tcg-store-service` ([line 1](../backend/docker-compose.desktop.yml:1)), assigns fixed `container_name` values to all eight services ([lines 54, 74, 90, 110, 140, 155, 184, 197](../backend/docker-compose.desktop.yml:54)), and pins persistent volume names `tcg-store-service_postgres_data` and `tcg-store-service_s3mock_data` ([lines 215–219](../backend/docker-compose.desktop.yml:215)). A second compose project name alone will not isolate the explicit container names or explicit volume names. Local and cloud backend services also declare the same `tcg-backend-service` network alias (lines 127–131 and 175–179); separate Compose projects naturally scope their networks, while one project should start only one profile.

Default host ports are PostgreSQL `5432` (line 59), backend `8080` (line 29), S3Mock `9090` (line 206), and frontend `3000` (line 193). PostgreSQL and S3Mock bind to loopback; backend and frontend bindings are not loopback-restricted. A fresh side-by-side environment needs its own ports. For durable isolation, use a unique project-scoped volume namespace and omit or parameterize fixed `container_name` and volume `name` values; retain the two existing volume identities as the reference for old local state.

## Workflow readiness

Local `git` refs show `feat/kubernetes` at `b859aa1`, eight commits ahead of local `origin/main` at `3338f76`; it is not merged to main. The image publish workflow runs on pushes to `main` (or manual dispatch), while the verifier and staging workflow require a successful main-branch run and staging dispatch on `refs/heads/main`. Do not use a feature-branch dispatch as the trusted candidate artifact. The exact successful trusted build run ID and image contract are not available from this local inspection; parent is checking GitHub state.

The relevant implementation points are [docker-compose.desktop.yml](../backend/docker-compose.desktop.yml:1) and [.env.example](../backend/.env.example:1). This report records inputs and isolation requirements only; no compose services, volumes, environment files, or remote resources were changed.
