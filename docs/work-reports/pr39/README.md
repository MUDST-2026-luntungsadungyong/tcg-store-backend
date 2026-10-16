# PR #39 handoff — primary checkout moved

Primary working directory: `C:\Users\User\tcg-store-backend`
Branch: `feat/kubernetes`, base commit `b859aa13689c87135dc2f9ce088e0c63d1c13560`.
Uncommitted implementation changes remain uncommitted; no push or merge was done.
The previous `feat/scrum-59-card-details-ui` branch remains intact.

This directory contains the reports produced before the move. Treat paths and
environment snapshots in older reports as historical. The active Component A
test plan is `pr39-component-a-test-plan.md`; no cluster fault/load test has run.

Sanitized logs, evidence and the verified frontend checkout were copied to the
ignored `.local/pr39-work` directory. Historical migration/removal helper scripts
there are archival and may contain paths to the old workspace: do not rerun them.
Use the current `scripts/start-desktop-acceptance.ps1`, not archival helpers.
The frontend source/build paths in Compose and `.env` now resolve inside this repo.

The working `.env` was copied without printing values. The previous destination
`.env` is preserved as ignored `.env.pre-migration-20261016`. Never commit either.
Existing `.neon` CLI linkage and upload data were left intact.

The old workspace remains available as a recovery copy. Compiled output and
dependency caches need not be migrated; they can be regenerated. Docker's data
disk remains on D: and all existing database/upload volumes are preserved.

Relocation verification is complete: all eight containers now reference this
checkout, frontend/backend-cloud are healthy and web/readiness/catalog/proxy GETs
returned HTTP 200. See `pr39-workspace-move.md` and the ignored
`.local/pr39-work/moved-runtime-verification.json` for the latest handoff.
