# PR #39 main sync analysis — 2026-10-16

## Scope and safe state

- PR: `feat(kubernetes)` → `main`, PR #39, currently open.
- Audited remote refs: `origin/main` at `96bc934b013b2f17b4b71a20ed4d3967971922b7`; `origin/feat/kubernetes` at `481db23d0218af325c69df7bd2f5bee09e37fa58`.
- Divergence at audit time: main has 3 commits not in the PR; the PR has 18 commits not in main. PR head is `481db23`; GitHub reports `mergeable_state=dirty`.
- Work was performed in a dedicated worktree at `C:\Users\Lenovo\tcg-store-backend-pr39-sync`, branch `codex/pr39-main-sync-analysis`. The primary checkout and its untracked files were not edited.
- A temporary `git merge --no-commit --no-ff origin/main` was used to enumerate conflicts, then `git merge --abort` completed successfully. No merge commit was created.
- No Docker, push, GitHub merge, or frontend/browser E2E was run.

## Divergence summary

The three main-only commits are:

1. `b453e8c fix(local): align order status bootstrap`
2. `f39483c test(SCRUM-30): add unit tests for remaining controllers to achieve 100% API coverage (#40)`
3. `96bc934 Merge pull request #42 ...`

Main's changes since the merge base touch nine paths: the PostgreSQL init schema, the seller-application notification migration, the Neon startup script, and six controller test classes. The six new tests cover admin dashboard, admin order logs, catalog cards, seller verification, store profile, and user profile. The schema and migration changes are auto-mergeable except for the migration's rename/edit interaction described below.

The PR's 18 commits include S3/Flyway migration foundations, Kubernetes reliability, supply-chain and release workflows, acceptance harnesses, and the live-acceptance reports. The current PR already contains Pakinnar's `2c5ea91 feat(acceptance): add desktop and reliability harnesses`; that commit is not the separate pending E2E commit mentioned by the team.

## Real conflicts and intended resolutions

The isolated merge trial found exactly two unmerged paths.

### 1. `scripts/start-neon-local.ps1`

Conflict hunk: the final Maven invocation. Main runs `compile spring-boot:run`; the Kubernetes branch adds a safe-start message and invokes `spring-boot:run` after validating Neon/JWT variables and stopping the Docker runtime services.

Recommended resolved content: retain all Kubernetes-branch validation and runtime-stop logic, keep the branch's non-secret status message, and retain main's explicit compile goal:

```powershell
$env:SPRING_PROFILES_ACTIVE = "neon"
Write-Output "Starting the backend from source with the Neon profile. Credentials are not displayed."
& .\mvnw.cmd compile spring-boot:run
exit $LASTEXITCODE
```

This preserves main's compile-before-run behavior and the branch's Neon configuration, credential checks, Docker port-conflict prevention, and non-disclosure message. The proposed combined script should be PowerShell-parsed after the real merge.

### 2. `src/main/resources/db/migration/V7__notification_seller_applications.sql`

Conflict hunk: the migration was moved from main's `infra/postgres/migrations/006_notification_seller_applications.sql` into the branch's Flyway V7 location, and both sides edited the foreign-key existence guard.

Recommended resolution:

- Keep the Flyway migration at `src/main/resources/db/migration/V7__notification_seller_applications.sql`; do not restore a second independently executed copy in `infra/postgres/migrations`.
- Keep the branch's stronger, table-scoped guard:

```sql
IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_notifications_seller_profile'
      AND conrelid = 'notifications'::regclass
) THEN
```

- Retain the rest of the Flyway migration, including its idempotent `ADD COLUMN IF NOT EXISTS`, constraint replacement, and index creation.
- Preserve main's separate init-schema change in the auto-merged Flyway baseline (`V1__baseline_schema.sql`). Do not resolve the migration conflict by taking all of main's side: that would discard the branch's Flyway path and table-scoped guard.

The two guards are not semantically equivalent: main checks only the constraint name; the branch also verifies that the named constraint belongs to `notifications`. The table-scoped branch guard avoids treating a same-named constraint on another table as proof that this foreign key already exists.

## Static checks performed

- PowerShell parser on the current branch `scripts/start-neon-local.ps1`: 0 parse errors.
- PowerShell parser on `origin/main:scripts/start-neon-local.ps1`: 0 parse errors.
- The merge trial was aborted; the worktree is clean and still based on PR head `481db23`.
- No Python test suite was run. No Docker/Kubernetes or cloud acceptance was run as part of this integration analysis.

## Safe integration procedure after the pending E2E commit lands

1. Confirm the E2E commit is pushed to `feat/kubernetes`; inspect its complete file list and diff, especially `.github/workflows/**`, `docker-compose.desktop.yml`, `scripts/smoke/**`, `scripts/start-neon-local.ps1`, fixtures, and shared test configuration.
2. Fetch the latest `origin/main` and `origin/feat/kubernetes`. Record both SHAs and re-check divergence; do not rely on the SHAs in this report if either ref moved.
3. Update the shared PR branch with a merge commit from the current `origin/main`. The branch is already public/shared, so preserve its published commit identities; avoid rebasing or force-pushing it.
4. Resolve the two known conflicts using the resolutions above. Re-enumerate `git status` for any new conflicts introduced by the E2E commit, and preserve both the new E2E behavior and existing Kubernetes/S3/Flyway/release behavior.
5. Review the final diff against both parents. Confirm the Flyway V7 migration exists only at the Flyway path, V1 includes main's schema adjustment, and the startup script contains both sides' intended behavior.
6. Run static checks first: PowerShell parser on `scripts/start-neon-local.ps1`; existing lightweight script/unit tests only when their dependencies are already installed; repository CI and Kubernetes manifest/release validation when appropriate. Do not treat static checks as proof of live cloud acceptance.
7. Re-run PR checks and inspect the updated PR mergeability. Stop before GitHub merge until the E2E commit is present and its checks are green.

Example commands once the E2E commit is available (run from the normal PR checkout, after coordinating the shared branch update):

```powershell
git fetch origin main feat/kubernetes
git switch feat/kubernetes
git merge --no-ff origin/main
# Resolve conflicts and inspect the complete staged/unstaged diff.
git status --short
git diff --check
```

The merge command is intentionally not run on the shared PR branch in this analysis.

## Risks from the pending E2E commit

At audit time GitHub still reported PR head `481db23`; no later, separate E2E commit was present in the PR. The existing `2c5ea91` is a desktop/reliability harness commit. The forthcoming E2E change may touch shared workflow triggers, Docker Compose, app startup/environment setup, API fixtures, or acceptance scripts. Those areas are adjacent to the main changes and could introduce additional textual or behavioral conflicts even where Git reports an automatic merge. After it arrives, re-fetch both refs, inspect its diff, repeat the merge trial, and rerun the affected tests before updating the shared branch.

## Recommended stopping point

Keep PR #39 open and do not merge or sync the shared branch yet. Wait for the separate E2E commit to land, then refresh both refs and repeat the conflict check against the updated E2E-inclusive branch. The analysis branch contains only this report commit and is not intended to replace or push the shared PR branch.
