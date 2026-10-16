# Local overlay prerequisites

Apply the separately maintained `infra/kubernetes/local-dependencies` resources
before running the migration Job. They provide the `postgres-local` Service on
port 5432 and `s3mock-local` Service on port 9090 in `tcg-store-local`; they are
not included in this application or migration overlay.

The PostgreSQL database is `tcg_store`. Create the local Secret
`tcg-store-backend-secrets` outside this source tree. Set `neon-jdbc-url` and
`neon-migration-jdbc-url` to `jdbc:postgresql://postgres-local:5432/tcg_store`,
and provide the API and migration username/password keys. No Secret values
belong in this repository.
