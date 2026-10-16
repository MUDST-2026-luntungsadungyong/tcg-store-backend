# Isolated database and object restore rehearsal

Run this rehearsal only against an isolated database restore target and a non-production object-storage destination. It measures observed recovery; it does not establish that a provider backup/PITR feature exists or is included in the current account.

This document is an operator procedure, not authorization to contact a provider or mutate an account. The named database and object-storage owners must use their approved consoles or tools and confirm each target before starting. Never place access tokens, connection strings, bucket credentials, customer identifiers, or raw object URLs in the evidence record.

## Preconditions

- An authorized database/platform owner confirms the actual backup or PITR mechanism, retention window, restore target, access path, and whether the selected account supports the requested restore point.
- An authorized object-storage owner confirms the real provider, endpoint identity, versioning/backup or immutable-copy mechanism, retention, and a safe isolated destination. Do not infer recovery capability from S3 compatibility or from a successful application health check.
- A recovery owner, observer, and approver are named. Define the requested RPO/RTO targets before starting; leave them explicitly `not-set` when the service owner has not approved targets.
- Production writes remain untouched. No restore command points at the production database or production bucket.

## Rehearsal procedure

1. Record the database backup/PITR reference and the object snapshot/manifest reference without recording credentials. Capture the selected database recovery point and object snapshot time.
2. Start the isolated database restore and record `restoreStartedAt`. Restore into a new branch/instance, never over the source. Record `restoreCompletedAt` when the target accepts connections and its schema/Flyway history can be inspected.
3. Start object recovery into a separate bucket/prefix or isolated provider target. Record its start/end timestamps and the exact source manifest/version reference. If no supported object recovery source exists, stop and record that limitation; do not fabricate a successful rehearsal.
4. Validate schema version, Flyway history, key relational constraints, and a bounded set of representative users/listings/carts/orders in the isolated DB. Do not export PII into the evidence report.
5. Compare referenced object keys from the isolated DB against the isolated object inventory/manifest. Verify existence and checksum/length where provider metadata supports it. Record missing, mismatched, and orphan counts without customer identifiers or raw object URLs.
6. Run the prior API compatibility check against the isolated data only. Record the exact image digest and sanitized result. Do not route public traffic to the rehearsal target.
7. Have the recovery owner review the evidence, clean up only the rehearsal resources under the approved retention policy, and retain the receipt/report for the required rollback evidence period.

Stop the rehearsal and record a failed or incomplete result if either restore source is unavailable, a target cannot be positively identified as isolated, any referenced object is missing or mismatched, schema/Flyway state is inconsistent, or the prior API compatibility check fails. Do not turn a database-only restore into a full-recovery pass. Cleanup must name only resources created for this rehearsal.

## Evidence record

Record these fields in the approved evidence system:

| Field | Value |
|---|---|
| Environment / rehearsal ID | Isolated environment and unique run ID |
| Recovery owner / observer / approver | Named operational owners |
| Database provider and restore reference | Provider-confirmed reference; no credentials |
| Database restore point | UTC time or provider reference |
| Database restore start / completion | UTC timestamps |
| Object provider and recovery reference | Confirmed provider mechanism and manifest/version reference |
| Object recovery start / completion | UTC timestamps |
| DB/object consistency | Referenced, present, missing, mismatched, and orphan counts |
| Observed RPO | Duration from selected restore point to the latest recoverable data observed |
| Observed RTO | Duration from restore start to verified database/object consistency and API compatibility |
| Target RPO / target RTO | Approved target values or `not-set` |
| Result / limitations / follow-up owner | Pass/fail with sanitized details and a named owner |

Compute RPO and RTO from the recorded timestamps and the agreed definitions for the actual provider. A rehearsal with database data but missing referenced objects is not a successful full data recovery. Do not mark production acceptance complete until owners confirm the account's actual backup, retention, restore, and object-recovery capabilities and approve measured results against targets.

Completion requires the database owner and object-storage owner to confirm the observed recovery point and consistency results, and the recovery owner to sign off on the measured RPO/RTO against approved targets. Missing targets remain `not-set`; do not label the rehearsal production-ready on the basis of a successful local or mocked exercise.
