# PR #39 release / observability / smoke round 1

Scope: first deterministic half of each in-scope Python test file, plus offline tool/browser feasibility checks. Repository was clean at start. No implementation files were changed. `tests/test_verify_artifact.py` was explicitly excluded because it belongs to the platform lane.

## Test selection and result

Selection rule: collect in pytest order and run the first `ceil(file total / 2)` node IDs in each file. Denominators include parameterized cases as pytest node IDs.

| File | Collected | Selected | Deferred |
|---|---:|---:|---:|
| `tests/test_receipts.py` | 25 | 13 | 12 |
| `tests/test_locking.py` | 9 | 5 | 4 |
| `tests/test_recovery.py` | 11 | 6 | 5 |
| `scripts/release/tests/test_orchestrate.py` | 12 | 6 | 6 |
| `scripts/release/tests/test_release_integration.py` | 9 | 5 | 4 |
| `infra/observability/tests/test_dashboard.py` | 4 | 2 | 2 |
| `scripts/smoke/tests/test_api_smoke.py` | 10 | 5 | 5 |
| **Total** | **80** | **42** | **38** |

Exact selected IDs and the reproducible runner are saved in [`run-selected.py`](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/work/round1-release/run-selected.py). From `backend/`, rerun with PowerShell:

```powershell
target/round1-tools/Scripts/python.exe ..\work\round1-release\run-selected.py
```

Reproducible rerun result: **42 selected, 42 passed, 0 failed, 0 skipped; exit code 0** (pytest: 3.62s). Raw output: [`pytest-selected.log`](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/work/round1-release/pytest-selected.log). Initial direct run also passed all 42 in 5.05s.

Deferred IDs are the remaining collected node IDs after the selected prefix in each file:

- `tests/test_receipts.py` (12): `test_promotion_requires_accepted_v2_receipt_and_matching_bundle`; `test_v1_is_recovery_only_and_cannot_carry_prune_inventory`; `test_bootstrap_and_migration_resources_are_excluded_from_inventory[Namespace]`; `[ServiceAccount]`; `[Secret]`; `[Job]`; `[ClusterRole]`; `test_prune_candidate_requires_exact_identity_and_live_owner_label`; `test_traefik_owned_resources_are_accepted_by_exact_gvk[Middleware]`; `[ServersTransport]`; `test_inventory_cannot_cross_environment_namespaces`; `test_bundle_inventory_requires_c_label_and_excludes_migration_job`.
- `tests/test_locking.py` (4): `test_acquire_assert_and_release_use_compare_and_swap`; `test_second_holder_cannot_take_live_lease`; `test_heartbeat_failure_is_exposed_by_assert_held`; `test_real_heartbeat_compare_and_swap_failure_is_reported`.
- `tests/test_recovery.py` (5): `test_post_migration_recovery_is_bounded[incompatible-previous2-recovery-required]`; `[backward-compatible-False-recovery-required]`; `test_boolean_cannot_claim_prior_image_receipt_and_bundle_are_verified`; `test_exact_inventory_delta_only_contains_release_owned_namespaced_kinds`; `test_live_prune_requires_exact_resource_and_c_owned_label`.
- `scripts/release/tests/test_orchestrate.py` (6): `test_observation_wait_polls_until_window_completes_while_asserting_lease`; `test_promotion_binds_receipt_to_trusted_run_id_not_workflow_source_sha`; `test_progress_receipt_requires_completed_migration`; `test_prune_requires_exact_live_identity_and_release_owner_label`; `test_restoration_frontend_origin_comes_from_retained_bundle_configmap`; `test_run_does_not_leak_github_token_to_kubectl`.
- `scripts/release/tests/test_release_integration.py` (4): `test_production_promotes_exact_staging_digest_and_retains_run_approval_linkage`; `test_rollout_failure_restores_verified_prior_bundle_without_running_old_migration`; `test_smoke_failure_restores_prior_bundle_and_keeps_pipeline_failed`; `test_success_persists_real_accepted_receipt_bundle_pointer_and_inventory_hash`.
- `infra/observability/tests/test_dashboard.py` (2): `test_panels_do_not_overlap_and_fit_the_dashboard_grid`; `test_expected_signal_families_are_present`.
- `scripts/smoke/tests/test_api_smoke.py` (5): `test_non_smoke_exception_text_never_reaches_report`; `test_production_mutation_is_refused_before_any_request_and_keeps_reason`; `test_readonly_baseline_against_mock_http_server`; `test_report_is_redacted_and_parent_directory_is_created`; `test_storage_public_base_url_requires_reviewed_https_same_host_and_no_secrets`.

## Additional checks and constraints

- Prometheus validation command `pwsh -NoProfile -File infra/observability/validate.ps1` stopped as designed: checksum-pinned `promtool.exe` is absent and `prom/prometheus:v3.15.0` is not present locally. It did not pull an image or contact a cluster. Docker CLI exists, but `docker info` could not connect to the Docker Engine named pipe (daemon unavailable).
- Frontend browser suite is only two read-only checks (`/cards` search and login password visibility) in `scripts/smoke/frontend_smoke.spec.js`. No `scripts/smoke/node_modules`, `TCG_FRONTEND_URL`, or `TCG_SMOKE_ENVIRONMENT` was available. No deployed URL was supplied; browser checks were not run and no live request was made. The full login → seller upload → catalog/cart/checkout browser journey is absent from this repo and is explicitly called an unfinished live gate in `docs/runbooks/full-stack-smoke.md`.
- The staging mutation API harness is present and has mocked coverage; live mutation requires exact independently verified database/bucket fixture identities, marker, dedicated accounts, and approved staging URL. Those protected inputs were not provided, so no API or cloud request was made.

## Findings and acceptance status

1. **Backup/restore evidence is not an enforced pre-migration gate (acceptance automation gap).** D6 step 3 requires evidence before mutation (`docs/kubernetes-component-d-observability-release-plan.md:177-185`), but `orchestrate()` validates artifact/readiness then renders and starts server admission/deploy without a backup/restore evidence input (`scripts/release/orchestrate.py:568-641`); its CLI ends without such an argument (`scripts/release/orchestrate.py:749-768`). The status still lists measured DB/object restore rehearsal as pending (`docs/kubernetes-component-d-implementation-status.md:74-76, 94-98`). This is a missing automated gate, not evidence of unsafe live behavior.
2. **Accepted receipts cannot bind full browser acceptance or frontend build identity (known incomplete acceptance path).** `_smoke()` invokes only `scripts/smoke/api_smoke.py` (`scripts/release/orchestrate.py:449-458`, called at `672-675`). The receipt captures API smoke and observation but no browser result/build identity (`scripts/release/orchestrate.py:706-726`), and the strict allowlist lacks frontend/browser evidence fields (`scripts/release/receipts.py:36-40`). D8 requires the staging browser flow plus frontend build ID (`docs/kubernetes-component-d-observability-release-plan.md:201-213`); the runbook/status explicitly say the full flow is not implemented (`docs/runbooks/full-stack-smoke.md:58-62`, `docs/kubernetes-component-d-implementation-status.md:80`). This is a documented acceptance gap, not a proven vulnerability; a separate manual gate could supply evidence, but the backend release receipt does not link it.
3. **A/C live gates are external and are not represented in the accepted release receipt.** D7 requires A/C live gates before production promotion (`docs/kubernetes-component-d-observability-release-plan.md:189-197`), including live admission/edge/network and runtime reliability gates in the plans. The current receipt allowlist covers release, smoke, observation, and inventory evidence only (`scripts/release/receipts.py:36-40`). Platform-run evidence may be reviewed manually outside this flow; there is no receipt linkage or automated gate in this backend release path. This is an external acceptance/evidence gap, not a proven vulnerability.
4. **Collector is intentionally an external prerequisite.** The release waits for collector-backed, rollout-bound observation evidence; collector installation and actual Prometheus/log/alert delivery remain platform-owner work per `docs/kubernetes-component-d-release-operations.md` and `docs/runbooks/observability.md`. No source defect inferred from its absence here.

Live staging/production gates remain unrun: no Kubernetes context, Docker Engine, Prometheus tool/image, protected release inputs, or deployed frontend target were available. The live acceptance plan prohibits treating local/mocked tests as proof of those gates.
