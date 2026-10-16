# Docker Desktop runtime

Use `docker-compose.desktop.yml`, project `tcg-store-service`. Both profiles
appear in Desktop; only one backend is active at a time.

- Cloud: frontend + backend-cloud, using Neon PostgreSQL and remote S3. The
  one-shot migrations-cloud gate must succeed before backend startup.
- Local: frontend + backend-local, PostgreSQL, S3Mock and migration/seed jobs.
  Development seed is never connected to the cloud profile.

```powershell
python scripts/initialize-desktop-env.py
# Fill missing cloud inputs in the ignored .env, never source or logs.
pwsh -NoProfile -File scripts/start-desktop-acceptance.ps1 -Runtime cloud -Build
```

The launcher defaults to cloud, creates both profiles without starting them,
stops inactive services, waits for the selected migration/backend gate, then
starts frontend. Use `-Runtime local` to switch. One-shot containers and inactive
local services may display Created/Exited while cloud is active; this is normal.

Defaults bind only to loopback: frontend `http://localhost:3000`, API
`http://localhost:8080/api`, local PostgreSQL `25432`, local S3Mock `9090`.
PostgreSQL avoids this host's Windows-reserved 15401-16400 range. Nginx proxies
`/api` to the selected backend's `tcg-backend-service` alias. Do not start both
backends: they share this alias and the host API port.

Frontend defaults to `.local/pr39-work/tcg-store-frontend`, currently main commit
`0273f7089172fc221ababb791d7bac47e3c48c14`. Its Desktop recipe uses Node 22,
skips Cypress binary downloads and excludes `.env*` via a Dockerfile-specific
ignore file. Vite variables are public inputs, never secret storage. The backend
uses a separate Maven cache ID to avoid the cache damaged by disk-full.

The initializer preserves existing `.env` and prints missing names only. Cloud
needs APP_JWT_SECRET, NEON_JDBC_URL, NEON_DB_USERNAME, NEON_DB_PASSWORD,
OBJECT_STORAGE_BUCKET, OBJECT_STORAGE_PUBLIC_BASE_URL, OBJECT_STORAGE_ACCESS_KEY,
OBJECT_STORAGE_SECRET_KEY, plus OBJECT_STORAGE_ENDPOINT for a custom provider.
Confirm region/path-style too. Local uses independent LOCAL_APP_JWT_SECRET and
LOCAL_OBJECT_STORAGE_* values. Presence does not prove connectivity/permissions.
Rotate credentials previously exposed in chat.

Cloud startup runs Flyway and Hibernate schema validation. Never reset, clean,
manually baseline or seed shared Neon to force the gate to pass. Review pending
migrations and backup requirements before a new version; an already-current V9
database has no pending V1-V9 scripts. Do not use `down -v` or broad Docker prune.
Old database/upload volumes are preserved. This is not production acceptance.
