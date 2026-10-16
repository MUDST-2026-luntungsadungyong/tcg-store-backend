# Optional local Kubernetes dependencies

This optional Kustomize overlay runs the Compose-pinned PostgreSQL and S3Mock images in the isolated `tcg-store-local` namespace. It does not change or manage Docker Compose. Each service uses its own Kubernetes PVC; neither PVC reuses a Docker volume. The overlay does not seed data, run migrations, or automatically delete stored data.

## Prerequisites and Secret files

Use an explicit Kubernetes context intended for local development and a StorageClass that can provision `ReadWriteOnce` PVCs. List configured contexts with `kubectl config get-contexts`, then set the chosen local context once in PowerShell:

```powershell
$LocalContext = '<your-local-context-name>'
```

The commands below pass that context explicitly. The manifests expect a Secret named `tcg-store-local-dependencies` in `tcg-store-local` with keys `postgres-db`, `username`, and `password`. Keep the Secret source file outside Git, for example in a user-protected local secrets directory, and restrict its file permissions. Do not put values in command arguments or commit the file.

Create a protected env file in a local editor with these key names and your chosen local database values:

```dotenv
postgres-db=<local database name>
username=<local database username>
password=<local database password>
```

The namespace is created by the overlay. Create it first so the Secret can be installed before the database workload:

```powershell
kubectl --context $LocalContext apply -f .\infra\kubernetes\local-dependencies\namespace.yaml
```

In PowerShell, create the Secret from that file through standard input; replace only the file path with its protected location:

```powershell
$dependencySecretFile = 'C:\secure-local\tcg-store-local-dependencies.env'
kubectl --context $LocalContext create secret generic tcg-store-local-dependencies `
  --namespace tcg-store-local `
  --from-env-file=$dependencySecretFile `
  --dry-run=client -o yaml |
  kubectl apply -f -
```

To connect the backend through the existing Kubernetes `neon` profile, create `tcg-store-backend-secrets` in the same namespace. Put these entries in a second protected env file; the runtime and migration database entries can point to the same local database:

```dotenv
neon-jdbc-url=jdbc:postgresql://postgres-local.tcg-store-local.svc.cluster.local:5432/<local database name>?sslmode=disable
neon-db-username=<same local database username>
neon-db-password=<same local database password>
neon-migration-jdbc-url=jdbc:postgresql://postgres-local.tcg-store-local.svc.cluster.local:5432/<local database name>?sslmode=disable
neon-migration-db-username=<same local database username>
neon-migration-db-password=<same local database password>
app-jwt-secret=<local application JWT secret>
object-storage-access-key=<OBJECT_STORAGE_ACCESS_KEY from your local user environment>
object-storage-secret-key=<OBJECT_STORAGE_SECRET_KEY from your local user environment>
```

Use only S3Mock fake credentials from the local user environment for the object-storage entries. Do not use cloud credentials. Keep the JDBC URL, database values, JWT secret, and storage keys in that protected file; create the backend Secret the same way, changing the file path and Secret name:

```powershell
$backendSecretFile = 'C:\secure-local\tcg-store-backend-secrets.env'
kubectl --context $LocalContext create secret generic tcg-store-backend-secrets `
  --namespace tcg-store-local `
  --from-env-file=$backendSecretFile `
  --dry-run=client -o yaml |
  kubectl apply -f -
```

The backend Secret contract has separate database keys: the API runtime uses `neon-jdbc-url`, `neon-db-username`, and `neon-db-password`; the migration Job uses `neon-migration-jdbc-url`, `neon-migration-db-username`, and `neon-migration-db-password`. For local development, all six may contain matching connection values for the same local database. Both JDBC URLs come from Secret key references. Use `sslmode=disable` only for these local PostgreSQL URLs. The API additionally reads `app-jwt-secret` and the two object-storage keys. The migration Job does not seed application data.

The existing `infra/kubernetes/overlays/local/prerequisites/configmap.yaml` sets `OBJECT_STORAGE_ENDPOINT` to `http://s3mock-local:9090`, `OBJECT_STORAGE_REGION` to `us-east-1`, `OBJECT_STORAGE_BUCKET` to `tcg-product-images-local`, `OBJECT_STORAGE_PUBLIC_BASE_URL` to `http://localhost:9090/tcg-product-images-local`, and path-style addressing to `true`. Keep the public base URL on localhost so browser image requests use the host port-forward. The local backend overlay uses the `neon` profile with `NEON_*` variables; its migration overlay is `infra/kubernetes/overlays/local/migration` and consumes the separate migration Secret keys above.

## Render, apply, and connect

Render without contacting or changing a cluster:

```powershell
kubectl kustomize .\infra\kubernetes\local-dependencies
```

Apply only when you intend to create the optional local dependencies:

```powershell
kubectl --context $LocalContext apply -k .\infra\kubernetes\local-dependencies
```

Forward S3Mock to `localhost:9090`:

```powershell
kubectl --context $LocalContext port-forward --namespace tcg-store-local service/s3mock-local 9090:9090
```

When the backend Service is deployed in `tcg-store-local`, expose its port `80` on host port `8080` with:

```powershell
kubectl --context $LocalContext port-forward --namespace tcg-store-local service/tcg-store-backend 8080:80
```

Keep `http://localhost:9090/tcg-product-images-local` as the public base URL so browser image requests use the S3Mock port-forward and the local overlay's bucket. Never point both Docker Compose and this overlay's PostgreSQL or S3Mock at the same data directory or PVC.

The existing local backend overlay and migration overlay both target `tcg-store-local`. Render or apply those overlays separately when you want to run the backend; this dependency overlay contains only PostgreSQL and S3Mock. Use the same explicit local context for those operations.

## Persistence and image behavior

PostgreSQL persists `/var/lib/postgresql/data` on `postgres-local-data`; S3Mock persists `/s3mockroot` on `s3mock-local-data`. Both PVCs are separate from Compose named volumes. Deleting a Deployment or Service does not delete its PVC. Deleting a PVC may cause the storage provider to retain or remove its backing data according to the cluster's StorageClass reclaim policy; inspect that policy and back up data before any manual PVC deletion. No seed Job, migration Job, cleanup Job, or automatic PVC deletion is included.

The exact image references match `docker-compose.desktop.yml`. Probe paths and commands mirror the health checks already defined there. Resource requests and limits are included to bound local cluster consumption. No container UID or unsupported security setting is imposed on the vendor S3Mock image; its supported identity and filesystem requirements are not established by the Compose contract. Keep this image local-only unless its vendor-specific runtime requirements and security behavior have been independently verified.
