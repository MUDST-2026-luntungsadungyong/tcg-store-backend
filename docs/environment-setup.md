# Environment setup

The repository uses one local runtime file: `.env`.

## Create the file

```powershell
Copy-Item .env.example .env
```

`.env.example` contains variables for local PostgreSQL, shared Neon, and
optional TiDB validation. Neon values do not select the active profile by
themselves. Compose helpers select the runtime; direct Spring runs use an
explicit profile. Keep real values in `.env`; `.env` and other `.env.*` files
are ignored by Git.

## Local PostgreSQL

For the full local Docker stack, use the helper. It stops the other backend
services, then starts local PostgreSQL, backend, and frontend from the canonical
Compose file:

```powershell
./scripts/use-local.ps1 -Build
Invoke-RestMethod http://localhost:8080/api/health
```

To run the backend from source instead, stop Docker backends first and start
only local PostgreSQL:

```powershell
docker compose -f docker-compose.desktop.yml --profile local --profile cloud stop backend-local backend-cloud s3mock
docker compose -f docker-compose.desktop.yml --profile local up -d postgres-local s3mock
```

Then run
`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (Windows:
`./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev`). Stop any running
backend Compose stack first so it releases port 8080.

## Shared Neon development

Do not copy a teammate's `.env` into the repository. Ask a teammate who has
access to the Neon project to either grant your Neon account project access,
or provide the connection details through the team's approved secret manager.
Never request or send database passwords, JWT secrets, or full connection
URLs in a pull request, issue, public chat, or committed file.

After access is available, use the helper to switch to Neon. It stops the
other backend services, refreshes `.env`, then starts the Neon backend:

```powershell
neon login
./scripts/use-neon.ps1 -Build
```

The script pulls the `development` branch values into `.env`, derives the
Spring Boot Neon variables, and generates a local JWT secret when the example
placeholder is still present.

## TiDB validation

Fill the TiDB variables in your local `.env` only when running TiDB validation.
Use a disposable or team-approved validation database, not production:

```powershell
$env:SPRING_PROFILES_ACTIVE = "tidb"
./mvnw.cmd --batch-mode -Dtest=TidbRevision5LiveValidationTests test
```

Before committing, confirm the runtime file is ignored:

```powershell
git check-ignore -v .env
```

## Switch between local and Neon

The repository uses one runtime file, `.env`, and one Compose file,
`docker-compose.desktop.yml`. Local starts PostgreSQL and S3Mock automatically;
Cloud uses Neon PostgreSQL and Neon Object Storage. Each backend publishes host
port 8080, so run one backend at a time. The frontend uses port 3000.

```powershell
# Start local PostgreSQL + S3Mock + backend + frontend
./scripts/use-local.ps1 -Build

# Start Neon backend + frontend, stopping other backends and refreshing .env
neon login
./scripts/use-neon.ps1 -Build
```

`use-neon.ps1` refreshes `.env` from the shared Neon `development` branch by
default. To reuse the current Neon values without another pull:

```powershell
./scripts/use-neon.ps1 -SkipEnvRefresh
```

To run only the backend from source against Neon, run
`./scripts/setup-shared-neon.ps1` followed by
`./scripts/start-neon-local.ps1`. The helper stops Docker backends first to
release host port 8080.
This loads `.env` into the process, checks required variable names without
displaying their values, and starts Spring with the `neon` profile.

To verify which Compose runtime is active:

```powershell
docker compose -f docker-compose.desktop.yml ps
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```
