# Environment setup

The repository uses one local runtime file: `.env`.

## Create the file

```powershell
Copy-Item .env.example .env
```

`.env.example` contains the variables for local PostgreSQL, shared Neon, and
optional TiDB validation. Keep only real values in `.env`; `.env` and all
other `.env.*` files are ignored by Git.

## Local PostgreSQL

Use the local defaults in `.env` and start the local stack:

```powershell
docker compose up --build -d
Invoke-RestMethod http://localhost:8080/api/health
```

## Shared Neon development

Do not copy a teammate's `.env` into the repository. Ask a teammate who has
access to the Neon project to either grant your Neon account project access,
or provide the connection details through the team's approved secret manager.
Never request or send database passwords, JWT secrets, or full connection
URLs in a pull request, issue, public chat, or committed file.

After access is available:

```powershell
neon login
./scripts/setup-shared-neon.ps1
docker compose -f docker-compose.neon.yml up -d --build
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
