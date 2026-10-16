# PR #39 audit — supply chain and renderer, remaining test halves

Checkout: `feat/kubernetes` at `b859aa1`; clean implementation tree. No source edits, deployment, cloud writes, credential access, commit, or push.

## Findings carried forward

- [`verify_artifact.py`](../backend/scripts/release/verify_artifact.py:197) accepts `enforce_admins.enabled == true` alone as proof that `main` is protected. That setting does not establish that review, status-check, or push restrictions exist. This is a demonstrated acceptance gap in the verifier; it is **not evidence that the current GitHub `main` branch is configured this way**. Parent independently reproduced acceptance of an `enforce_admins`-only response using the successful verifier fixture. Do not treat the implementation issue as fixed in this audit round.
- Component B still needs a successful trusted GitHub run that publishes and verifies the GHCR multi-arch index, signature, provenance, child SPDX attestations, and digest handoff ([plan](../backend/docs/kubernetes-component-b-container-supply-chain-plan.md:3)).
- Component C still needs target-cluster admission and live edge/network acceptance. Earlier checks found no Kubernetes contexts and no Docker engine pipe. The public IPv4 egress exception is documented but must be resolved or explicitly accepted by the platform owner before production ([contract](../backend/docs/kubernetes-component-c-platform-contract.md:22)).

## Remaining selected checks

| Lane | Selected / total | Result | Log |
|---|---:|---|---|
| Artifact verifier | Remaining 5 / 9 cases | **5 passed** | `../work/round2-platform/artifact-remaining.log` |
| Renderer policy | Remaining 15 / 30 methods | Initial run: 14 passed, 1 conditional skip without schema env. Final pinned run: **15 passed, 0 skipped**. Elevated filesystem access was needed for `kubectl kustomize`; pytest emitted a cache permission warning. | Initial: `../work/round2-platform/renderer-remaining.log`; final: `../work/round2-platform/renderer-remaining-pinned.log` |

Exact rerun commands from the backend directory:

```powershell
& .\target\round1-tools\Scripts\python.exe ..\work\round1-platform\run-selected.py ..\work\round2-platform\artifact-remaining-ids.txt
$env:PATH = ((Resolve-Path .\target\round2-bin).Path + ';' + $env:PATH)
$env:KUBECONFORM_PATH = (Resolve-Path .\target\round2-bin\kubeconform\kubeconform.exe).Path
$env:TCG_TRAEFIK_SCHEMA_DIR = (Resolve-Path ..\work\round2-tools\traefik-schemas).Path
& .\target\round1-tools\Scripts\python.exe ..\work\round1-platform\run-selected.py ..\work\round2-platform\renderer-remaining-ids.txt
```

The runner passes each node ID in the corresponding text file as a separate pytest argument. Those files contain the exact remaining-half selections. Renderer command was run with elevated filesystem access; artifact command used the standard sandbox.

The final renderer rerun used pinned kubectl **v1.34.1** / Kustomize **v5.7.1**, kubeconform **v0.7.0**, and 10 schemas generated from the official Traefik v3.5.0 CRD. `KUBECONFORM_PATH` and `TCG_TRAEFIK_SCHEMA_DIR` were set, so the conditional test ran and all 15 passed. Direct strict checks also rejected each malformed fixture against the expected schema rule, with zero missing schemas/errors and zero skipped resources: `wrongField` was rejected as an additional property, and string `maxRequestBodyBytes` was rejected because an integer is required. Logs: `../work/round2-platform/middleware-wrong-field.log` and `../work/round2-platform/middleware-wrong-type.log`.

## Completed test selection inventory

Across round 1 and round 2, all 9 artifact verifier cases passed. With the pinned final rerun, all 30 renderer methods passed across both halves, including the Traefik negative-schema test. The deployment helper’s complete mocked scenario script passed in round 1; it is separate from these pytest counts and is not cluster acceptance. No synthetic result is being represented as live acceptance.

The remaining live work is unchanged: trusted registry publishing and verification, staging platform setup and API-server admission, Secret/RBAC delivery, TLS/routing/CORS/upload/rate-limit behavior, NetworkPolicy enforcement and external database/object-storage connectivity. Production egress destination policy also needs platform-owner resolution.
