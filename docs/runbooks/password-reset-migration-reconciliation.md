# Password recovery prerelease reconciliation (2026-11-12)

Password recovery was initially applied on the shared development Neon branch
as V27. Concurrently, main introduced canonical notification V27 and attachment
V28. The release uses V29 for password recovery. Canonical V27/V28 are unchanged.

The one affected Neon database was reconciled under an exclusive Flyway-history
lock in a bounded transaction. The operation first asserted the exact prerelease
V27 filename and Flyway checksum, then validated the existing recovery schema
using V29. Its existing history row retained its installation rank and timestamp
and was moved to V29 with the V29 filename/checksum. Canonical V27 and V28 were
then executed and recorded. No user password was changed.

The complete operation was rehearsed with rollback before commit. Aggregate
fingerprints and counts of users (33), orders (42), and notifications (36) were
identical before and after. The outbox was empty. Local audit artifacts are kept
outside the repository in the workspace `.scratch` directory. This is an
environment-specific repair, not a deployment migration or a general instruction
to alter Flyway history. Other databases apply canonical V27–V29 normally.

V29 accepts the already-created schema with explicit type/nullability/constraint
guards; incompatible existing definitions fail closed. Deployment must run
Flyway validation and Hibernate schema validation using the final packaged app.
