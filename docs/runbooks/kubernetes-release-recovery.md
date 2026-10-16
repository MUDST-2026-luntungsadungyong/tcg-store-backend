# Kubernetes release recovery

Use this runbook for a failed staging or production release. The workflow must keep the failed release result failed even when application rollback succeeds. Do not reverse Flyway migrations or run `flyway clean` as part of application recovery.

## Before acting

Record the environment, namespace, failed release ID, source commit, build/deploy run IDs, phase, request IDs, and receipt path. Confirm that the release receipt contains no credentials or customer data. Stop promotion and prevent another deploy to the same environment while recovery is in progress.

Use only the explicit Kubernetes context and namespace recorded for that environment. Verify the current release inventory and compare it with the last accepted receipt. Do not use a namespace-wide prune, a broad label selector, or `kubectl rollout undo` as a substitute for restoring the prior approved bundle.

The release Lease must still be held by the current release ID before any recovery mutation. The orchestrator must call `LeaseLock.assert_held()` immediately before applying or deleting resources. If ownership, renewal, or the explicit context cannot be confirmed, stop and mark `recovery-required`.

## Recovery decision

| Failure phase | Action |
|---|---|
| Artifact verification, render, or admission before mutation | Reject the release; make no cluster changes. |
| Prerequisite or non-secret configuration apply | Restore the previous configuration only after independently verifying the prior receipt and retained bundle. Otherwise mark `recovery-required`. |
| Migration failed or its final schema state is unclear | Stop before application rollout. Inspect migration logs and schema/Flyway state through the authorized database operator. Do not reverse SQL automatically. Mark `recovery-required` until a reviewed forward fix or isolated restore plan is approved. |
| Rollout or smoke failed after migration | Restore the previous application bundle only when the migration is explicitly backward-compatible, the prior image signature/provenance/SBOM are verified, and the prior receipt and exact configuration bundle are available. |
| Compatibility is unknown/incompatible, prior bundle is missing, or rollback failed | Stop and mark `recovery-required`; use a reviewed forward-fix or isolated data-restore procedure. |
| Monitoring data is stale or absent | Hold promotion and investigate telemetry freshness. Do not roll back a healthy application solely because metrics are missing. |

The recovery planner's `has_verified_previous` input must be a mapping with all three flags (`imageVerified`, `receiptVerified`, and `configurationBundleVerified`) set to true. A boolean is insufficient. Reverify the prior image signature/provenance/SBOM by its retained immutable digest, validate its receipt, and recompute its retained bundle before setting those flags. A migration failure always requires investigation because partial schema changes may have occurred.

## Restore an approved configuration

1. Load the last accepted receipt and retained bundle. Recompute the bundle hash from the exact `application.yaml`, `migration.yaml`, and `prerequisites.yaml` bytes, in that filename order, with a NUL byte after each filename. Recompute the ConfigMap hash from canonical JSON (`sort_keys=True`, compact separators).
2. Confirm environment, namespace, image index digest, source commit, config hash, and managed-resource inventory against the accepted receipt. Schema v2 must match its embedded hashes. Legacy schema v1 is usable only when its retained files are present and the recomputed hashes match the receipt.
3. Schema v1 receipt compatibility is recovery-only. Its resource ownership is unknown, so it never authorizes automatic prune. For v2, reconcile exact resource identities (API version, kind, namespace, name) from release-controlled kinds only. Before each delete, fetch that exact live resource and require its identity to match and its top-level `app.kubernetes.io/managed-by` label to equal `tcg-release`. If the object is absent, mismatched, unlabeled, or labeled differently, do not delete it; stop for review. Namespace, ServiceAccount, Secret, and migration Job are excluded from inventory and automatic prune. Never prune an entire namespace or platform-owned resources.
4. Apply the prior approved bundle using the environment's explicit context. Wait for the previous Deployment to become ready, then run the environment's read-only health/smoke checks and inspect logs/metrics for the observation interval.
5. Record the recovery attempt in a new receipt with status `rolled-back` only if the previous release is healthy. Preserve the failed release receipt and mark the original failed run as failed. If any restore or health check fails, record `recovery-required` and stop automated retries.

Rollback restores application/configuration state only. It does not undo SQL changes, recreate rotated credentials, or restore deleted object data. Treat each as a separate recovery decision.

Migration is never rerun as a recovery action. A failed or partially completed migration requires schema/Flyway inspection by the database owner and a reviewed forward fix or isolated restore decision. Do not invoke the failed release's migration Job again, reverse SQL automatically, or run `flyway clean`.

## Object/database consistency

A database restore can restore object keys in rows without restoring the corresponding objects. Before declaring recovery complete, compare object references in the isolated restored database with the provider's object inventory/manifest, verify a sample of referenced objects by exact key and content checksum where available, and report missing or orphaned objects. Do not copy or delete production objects during a rehearsal.

Use the separate [data restore rehearsal](data-restore-rehearsal.md) to validate an isolated database restore together with object consistency. No provider backup, PITR entitlement, object versioning, retention period, or recovery capability is assumed by this repository.

## Escalation and evidence

Escalate when schema state is uncertain, the prior bundle cannot be verified, restore checks fail, or the exact production object/data recovery capability is unknown. Keep credentials and Secret values out of receipts, artifacts, chat, and command output. Store provider references and access-control metadata only in the approved operations system.
