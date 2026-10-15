# Docker Desktop: Local and Cloud Backends

The desktop Compose stack creates two clearly named backend containers:

- `tcg-store-service-backend-local` connects to `tcg-store-service-postgres-local`.
- `tcg-store-service-backend-cloud` connects to Neon using the `neon` profile.

Both backends publish host port `8080`. Only one backend can run at a time.
Stop the active backend in Docker Desktop before starting the other one. The
local PostgreSQL container can remain running while the cloud backend is active;
the cloud backend does not connect to it.

## Initial setup

```powershell
docker compose -f docker-compose.desktop.yml --profile local --profile cloud config --quiet
docker compose -f docker-compose.desktop.yml --profile local up --build -d postgres-local backend-local frontend
docker compose -f docker-compose.desktop.yml --profile local stop backend-local
docker compose -f docker-compose.desktop.yml --profile cloud up --build -d backend-cloud frontend
```

The initial setup leaves the cloud backend running and the local backend
stopped. Existing named volumes are reused, so recreating the containers does
not delete local database or uploaded-image data.

## Switch with Docker Desktop

1. Open the `tcg-store-service` group under **Containers**.
2. Stop `tcg-store-service-backend-cloud`.
3. Start `tcg-store-service-backend-local`.
4. To switch back, stop local before starting cloud.

S3Mock is part of the Local profile and starts automatically with
`backend-local`. There is no filesystem backend and no separate S3 toggle.

## Verify the active backend

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
docker exec tcg-store-service-backend-local printenv SPRING_PROFILES_ACTIVE
docker exec tcg-store-service-backend-cloud printenv SPRING_PROFILES_ACTIVE
```

The local container reports `dev`; the cloud container reports `neon`. A
stopped container cannot be queried with `docker exec`, so run the command only
against the active backend.

## CLI switching

```powershell
# Cloud to local
docker stop tcg-store-service-backend-cloud
docker start tcg-store-service-backend-local

# Local to cloud
docker stop tcg-store-service-backend-local
docker start tcg-store-service-backend-cloud
```

Do not run `docker compose down -v` for this workflow. The `-v` option deletes
the named volumes that hold the local PostgreSQL and uploaded-image data.
