# PR #39 release / observability / smoke round 2

Scope: exact complement of the 42 node IDs in round 1 across the same seven suites. Round 1 IDs were read from `work/round1-release/run-selected.py`; suites were recollected with pytest before selecting this complement. Branch/HEAD observed: `feat/kubernetes` / `b859aa1`; worktree clean. No implementation files changed. No deployment, live request, or external mutation.

## Results

| File | Collected | Round 1 | Round 2 | Remaining |
|---|---:|---:|---:|---:|
| `tests/test_receipts.py` | 25 | 13 | 12 | 0 |
| `tests/test_locking.py` | 9 | 5 | 4 | 0 |
| `tests/test_recovery.py` | 11 | 6 | 5 | 0 |
| `scripts/release/tests/test_orchestrate.py` | 12 | 6 | 6 | 0 |
| `scripts/release/tests/test_release_integration.py` | 9 | 5 | 4 | 0 |
| `infra/observability/tests/test_dashboard.py` | 4 | 2 | 2 | 0 |
| `scripts/smoke/tests/test_api_smoke.py` | 10 | 5 | 5 | 0 |
| **Total** | **80** | **42** | **38** | **0** |

All **38 selected cases passed**, 0 failed, 0 skipped; exit code 0 (pytest: 2.98s). Structured runner output: `selected=38, passed=38, failed=0, skipped=0, exitCode=0`.

The exact complementary IDs are in [`run-selected.py`](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/work/round2-release/run-selected.py). Reproduce from `backend/`:

```powershell
target/round1-tools/Scripts/python.exe ..\work\round2-release\run-selected.py
```

Raw output: [`pytest-selected.log`](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/work/round2-release/pytest-selected.log).

## New findings

No new failures or findings surfaced in this complementary half. This completes the 80 collected Python cases for the seven scoped suites across rounds 1 and 2. `tests/test_verify_artifact.py` remains excluded because it belongs to the platform lane; the parent separately reran the production promotion case.

The previously documented gaps remain: full browser login/upload/cart/checkout journey and frontend build identity are not wired into the release receipt; backup/restore and A/C live acceptance evidence are not enforced or linked by the backend receipt. These are acceptance/evidence gaps, not newly discovered vulnerabilities. Collector/exporter/log/alert setup and all live Kubernetes, Neon, object storage, protected artifact, frontend deployment, and restore acceptance remain platform/external prerequisites.

## Offline tooling references for parent review

- The Windows observability validator expects Prometheus `v3.15.0` at `target/observability/prometheus-3.15.0.windows-amd64/promtool.exe`, with executable SHA-256 `b02a6d8726533fbf59d40fad83646105429b6ed2f1b928f9d44659c67a83659b`; the matching Windows release archive checksum documented in `docs/runbooks/observability.md:30` is `5d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e`. Then run `pwsh -NoProfile -File infra/observability/validate.ps1`.
- CI pins Linux Prometheus `v3.15.0`; archive `prometheus-3.15.0.linux-amd64.tar.gz` SHA-256 is `2a542df32eac02ee17b9d844fb2aa1de00dafa5476579ba8a3ba862e9d572ea0` (`.github/workflows/release-validation.yml:62-80`). The direct validator commands are `promtool check config infra/observability/prometheus.yml`, `promtool check rules infra/observability/rules/recording.yml infra/observability/rules/alerts.yml`, and `promtool test rules infra/observability/tests/alerts.test.yml`.
- CI pins actionlint `v1.7.12` installed with `go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.12`, with Go `1.25.3`; command: `actionlint -config-file .github/actionlint.yaml .github/workflows/deploy-staging.yml .github/workflows/deploy-production.yml .github/workflows/release-validation.yml` (`.github/workflows/release-validation.yml:54-75`).

No tooling binary was downloaded or executed in this round; parent is handling those checks.
