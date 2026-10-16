# Step 1 local S3 acceptance — 2026-10-16

Run completed at 2026-10-16 17:39:11 +07:00 (elapsed 262s).
Image: `tcg-accept-20261016-backend:flyway-fixed`; Docker image ID `sha256:092994053b1ede5d54eafd5dfcadb2f8251e40f030dcf6d9803d68b2040bbb60`; runtime source revision `b859aa1-flyway-fix`.

All resources were isolated under the ``tcg-accept-20261016-step1`` prefix. Existing local cloud backend and frontend were not targeted.

## Results

- PASS: isolated S3Mock started with a task-scoped bucket and persistent task-scoped volume.
- PASS: current-source migration runner completed against isolated Postgres.
- PASS: isolated PostgreSQL Flyway history records all 9 successful migrations, including version 9.
- PASS: replica A became ready with Hibernate schema validation against the Flyway-created schema.
- PASS: both backend replicas became ready with Hibernate schema validation against the same Flyway-created Postgres schema and S3Mock.
- PASS: replica A accepted a validated PNG, uploaded it to S3Mock, and S3 GET returned the exact fixture bytes.
- PASS: replica B GET returned the shared product metadata and managed image URL before deletion.
- PASS: deleting one of two products preserved the shared S3 object and remaining database reference.
- PASS: final product delete through replica B removed the now-unreferenced S3 object after commit.
- PASS: forced Postgres insert failure rolled back the API transaction and removed its newly uploaded object.
- PASS: spoofed image bytes were rejected before upload; S3 object inventory did not change.
- PASS: retained a synthetic product image to verify persistence across S3Mock restart.
- PASS: both current-source backend replicas restarted after S3Mock; readiness recovered before the retained-object check.
- PASS: uploaded object remained readable by a fresh S3 client after isolated S3Mock restart.
- PASS: retained fixture was read after restarting S3Mock and both backend replicas; the persisted bytes matched the original fixture.
- PENDING: Kubernetes/cloud replica parity is outside this isolated local acceptance run.

No environment values, credentials, JDBC URLs, tokens, or response/log bodies are recorded.
