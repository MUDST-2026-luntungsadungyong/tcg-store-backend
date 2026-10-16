# Step 1–2 acceptance harnesses

These scripts exercise real services. They are separate from mocked unit tests.
See [reviewed results](../../docs/kubernetes-step-1-2-live-acceptance-results.md) and
[the execution plan](../../docs/kubernetes-live-acceptance-plan.md).

## Local prerequisites

- Docker Desktop with the test backend image `tcg-accept-20261016-backend:current` built from the intended source revision.
- PostgreSQL 16 and S3Mock images for local tests; PostgreSQL 18 image for the Neon snapshot restore rehearsal.
- PowerShell 7 and Python 3.13. Install `requirements.txt` into an ignored virtual environment:

```powershell
python -m venv target/acceptance-tools
target/acceptance-tools/Scripts/python.exe -m pip install -r scripts/acceptance/requirements.txt
```

## Local execution

```powershell
$image = 'tcg-accept-20261016-backend:flyway-fixed'
$expectedImage = 'sha256:092994053b1ede5d54eafd5dfcadb2f8251e40f030dcf6d9803d68b2040bbb60'
./scripts/acceptance/step1-local.ps1 -Image $image -ExpectedImageId $expectedImage -EvidencePath docs/evidence/step1-local-fixed-2026-10-16.md
./scripts/acceptance/step2-local.ps1 -Image $image -ExpectedImageId $expectedImage -EvidencePath docs/evidence/step2-local-fixed-2026-10-16.md
```

Resources use the `tcg-accept-20261016` prefix. Scripts refuse existing resource
collisions and remove only the disposable resources they create. Step 1 requires
unused loopback ports 15432, 19090, 18081 and 18082. Reports go to `docs/evidence/`.
The test password/JWT/S3 keys in these local fixtures are synthetic, isolated values.

Step 1 now stops before API startup if the migration runner or the nine successful
Flyway history records are missing. Both API replicas use `ddl-auto=validate`.
The earlier Hibernate bootstrap workaround is removed. Step 2 additionally rejects
a deliberately corrupted migration checksum on an otherwise valid existing schema.

The example image ID identifies this local retest build; supply the reviewed ID of
your new image when rebuilding. Do not substitute an arbitrary local tag for a
published release digest in staging/production.

Run the Java regression suite in a checkout with Maven-owned build outputs:

```powershell
.\mvnw.cmd test
```

If an IDE writes error-stub classes into `target/classes` or `target/test-classes`,
isolate the Maven output directory before rerunning. The reviewed full-suite run
used generated validation metadata under ignored `target/flyway-validation-pom/`
and outputs under `target/flyway-isolated-verification/`; source and dependencies
were the same as the main POM.

## Cloud execution

Cloud scripts privately load the configuration of the existing
`tcg-store-service-backend-cloud` container. Review that target before running.

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-readonly-audit.py --output target/acceptance-20261016/cloud-readonly.json
target/acceptance-tools/Scripts/python.exe scripts/acceptance/secret-value-audit.py
```

`cloud-readonly-audit.py` uses a read-only, certificate-verified database
connection and lists/reads existing objects. `secret-value-audit.py` compares
known private values in memory and emits only booleans/file paths.

`cloud-object-roundtrip.py` **writes and deletes one new UUID PNG object** in the
configured bucket. It never changes database references or existing objects:

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-object-roundtrip.py
```

`cloud-backup-restore.py` reads a consistent snapshot of the Neon `public` schema,
holds the archive in memory, and restores into a new local container. Credentials
are passed privately through the child process environment. It cleans up that
new container/storage/network; it does not restore or migrate the source Neon DB:

```powershell
target/acceptance-tools/Scripts/python.exe scripts/acceptance/cloud-backup-restore.py --backend-image tcg-accept-20261016-backend:flyway-fixed --postgres-image postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873 --output target/acceptance-20261016/cloud-backup-restore-fixed.json
```

Aggregate cloud results are stored under ignored `target/acceptance-20261016/`.
No raw database archive or application credentials should be committed.
