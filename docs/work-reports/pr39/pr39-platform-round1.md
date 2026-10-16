# PR #39 audit — supply chain, renderer, deployment adapter

Checkout: `feat/kubernetes` at `b859aa1`; initially clean. No implementation files changed.

## Findings

- **Verifier protection check is too permissive:** [verify_artifact.py](../backend/scripts/release/verify_artifact.py:197) accepts `enforce_admins.enabled == true` by itself as evidence that `main` is protected. That flag only says branch-protection rules apply to admins; by itself it does not establish a required review, status check, or push restriction. The verifier can therefore accept a successful push workflow from `main` without establishing the protected-main gate required by B-01. Require evidence of an actual protection rule and add a negative unit test for an `enforce_admins`-only response.
- **Known egress exception remains a production decision:** API and migration policies allow public IPv4 destinations on TCP 5432/443 with excluded ranges ([api-egress.yaml](../backend/infra/kubernetes/components/network/api-egress.yaml:27), [migration-egress.yaml](../backend/infra/kubernetes/components/network/migration-egress.yaml:27)). The platform contract documents this as broad port-limited egress and says to use an FQDN policy/egress gateway or explicitly accept the destination risk before production ([contract](../backend/docs/kubernetes-component-c-platform-contract.md:22)). This is documented behavior, but still requires platform-owner resolution before production acceptance.
- Component B remains incomplete until a protected-main GitHub run publishes and verifies the GHCR multi-arch index, signature, provenance, child SPDX attestations, and digest handoff. The plan marks these pending ([B plan](../backend/docs/kubernetes-component-b-container-supply-chain-plan.md:3), [B-01–B-05](../backend/docs/kubernetes-live-acceptance-plan.md:143)).
- Component C live acceptance remains incomplete: no Kubernetes contexts are configured, Docker cannot connect to `//./pipe/docker_engine`, and API-server/CNI/Traefik/TLS/Secret prerequisites are external. Required live gates are listed in [C-01–C-09](../backend/docs/kubernetes-live-acceptance-plan.md:159).

## Selected offline checks

| Lane | Selected / total | Result | Log |
|---|---:|---|---|
| Artifact verifier | 4 / 9 collected cases | **4 passed** | `../work/round1-platform/artifact-first-half.log` |
| Renderer policy | 15 / 30 methods | **15 passed** when run with elevated filesystem access. The sandbox run errored during shared setup because `kubectl kustomize` received Windows `Access is denied` reading the rendered directory. The elevated run emitted a pytest cache warning because `.pytest_cache` is not writable, but all assertions passed. | `../work/round1-platform/renderer-first-half-15-elevated.log`; raw sandbox stderr: `../work/round1-platform/kubectl-sandbox-raw.log` |
| Deployment adapter | Full scenario script (indivisible) | **Passed**: mocked migration gate/order, explicit context, names-only Secret preflight, CI readiness/Lease guards, stale readiness rejection. This is an offline/mock gate, not cluster acceptance. | `../work/round1-platform/deploy-adapter-scenario-suite.log` |

Exact artifact selection (4 of 9 pytest cases; remaining five are listed below):

```powershell
& .\target\round1-tools\Scripts\python.exe -m pytest -q `
  tests/test_verify_artifact.py::test_default_zip_regular_permissions_are_accepted `
  tests/test_verify_artifact.py::test_zip_symlink_is_rejected `
  tests/test_verify_artifact.py::test_index_child_digests_must_match_actual_registry_manifest `
  tests/test_verify_artifact.py::test_command_errors_never_echo_credentials_or_stderr
```

Renderer command: `& .\target\round1-tools\Scripts\python.exe -m pytest -q` followed by these first 15 explicit node IDs in source order from `scripts/kubernetes/test_render.py`:

```text
KubernetesRenderPolicyTests::test_clean_render_passes
KubernetesRenderPolicyTests::test_release_ownership_is_required_on_controlled_resources
KubernetesRenderPolicyTests::test_telemetry_cannot_claim_a_different_environment
KubernetesRenderPolicyTests::test_v2_requires_explicit_telemetry_environment
KubernetesRenderPolicyTests::test_release_labels_do_not_claim_bootstrap_or_migration_jobs
KubernetesRenderPolicyTests::test_monitoring_policy_requires_namespace_and_pod_selector_together
KubernetesRenderPolicyTests::test_unapproved_resource_is_rejected
KubernetesRenderPolicyTests::test_api_and_migration_image_mismatch_is_rejected
KubernetesRenderPolicyTests::test_mutable_image_tag_is_rejected
KubernetesRenderPolicyTests::test_secret_resource_is_rejected
KubernetesRenderPolicyTests::test_config_credential_field_is_rejected_recursively
KubernetesRenderPolicyTests::test_public_service_is_rejected
KubernetesRenderPolicyTests::test_catchall_ingress_is_rejected
KubernetesRenderPolicyTests::test_wildcard_cors_is_rejected
KubernetesRenderPolicyTests::test_configmap_credential_key_is_rejected
```

Rerunnable selected-ID files and runner are in `../work/round1-platform/renderer-first-half-ids.txt`, `../work/round1-platform/artifact-first-half-ids.txt`, and `../work/round1-platform/run-selected.py`. From the backend directory, run `& .\target\round1-tools\Scripts\python.exe ..\work\round1-platform\run-selected.py ..\work\round1-platform\renderer-first-half-ids.txt` (substitute the artifact list for the artifact half). Sandbox comparison: `kubectl.exe` v1.36.1 / Kustomize v5.8.1 returned exactly `error: must build at directory: not a valid directory: evalsymlink failure on 'C:\Users\User\Documents\Codex\2026-10-16\https-github-com-mudst-2026-luntungsadungyong-2\backend\infra\kubernetes\overlays\staging' : Access is denied.` with exit code 1; see `../work/round1-platform/kubectl-sandbox-raw.log`. The same read-only command succeeded with elevated filesystem access and emitted the expected manifests. The exact selected elevated test result is in its log.

PowerShell command:

```powershell
& 'C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File scripts/tests/test-deploy-kubernetes.ps1
```

The adapter script prints one PASS summary and exits 0. Its case-level output is suppressed by the script, so report it as a passed offline scenario suite rather than a count of separate tests.

## Remaining half of selected test files

Artifact verifier (5 collected cases):

- `test_run_identity_is_fail_closed[run0]`
- `test_run_identity_is_fail_closed[run1]`
- `test_run_identity_is_fail_closed[run2]`
- `test_prior_image_reverification_does_not_require_build_artifact`
- `test_verify_release_accepts_only_protected_main_run_and_exact_artifact`

Renderer methods (15 of 30):

- `test_literal_database_password_environment_value_is_rejected`
- `test_missing_middleware_is_rejected`
- `test_broadened_network_policy_is_rejected`
- `test_placeholder_release_config_is_rejected`
- `test_wrong_ingress_class_is_rejected`
- `test_production_test_domain_requires_template_mode`
- `test_fixture_marker_cannot_be_cleared_to_deploy_test_domain`
- `test_local_settings_do_not_require_release_config`
- `test_versioned_bundle_separates_bootstrap_and_ci`
- `test_ci_inputs_cannot_add_bootstrap_or_unapproved_resources`
- `test_render_hash_detects_post_validation_manifest_changes`
- `test_legacy_bundle_is_readable_but_unknown_version_is_rejected`
- `test_approved_legacy_cloud_bundle_is_recovery_readable`
- `test_crd_schema_closes_nested_objects_and_keeps_scalar_types`
- `test_kubeconform_rejects_malformed_traefik_middleware` (conditionally skips unless schema/binary env vars are supplied)

## Current capability and live prerequisites

Local source defines a schema-v2 renderer, immutable image contract verifier, and deployment adapter with migration-before-API and lease checks. Selected artifact tests, selected renderer tests, and the full mocked adapter scenarios passed. These checks do not establish a published GHCR artifact, admission against a real API server, Secret/RBAC behavior, TLS/routing, NetworkPolicy enforcement, live storage/database connectivity, or deployment acceptance.

Before B/C live work: a successful trusted workflow run on protected `main`; verified GHCR index and child digests/attestations; a staging Kubernetes context with namespace, Traefik v3/CRDs, cert-manager issuer/TLS, policy-enforcing CNI, external Secret delivery and readiness metadata; staging database/object-storage inputs and test identities. Platform ownership must resolve or explicitly accept broad public IPv4 egress. The remaining offline halves are listed above. The initial renderer sandbox issue is understood and its selected half passed in the elevated read-only run.

The reports and logs are offline audit evidence only; no tests used a mock result to claim live acceptance. `git diff --check` passed and the worktree remained clean.
