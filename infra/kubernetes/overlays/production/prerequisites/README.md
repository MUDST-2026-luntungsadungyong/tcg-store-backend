# Production overlay prerequisites

Replace every `example.invalid` host and `sha-REPLACE_ME` image input with
platform-approved values before deployment. Create the `tcg-store-backend-secrets`
Secret through the approved external Secret provider/release process, including
the `neon-migration-jdbc-url`, `neon-migration-db-username`, and
`neon-migration-db-password` keys for the migration Job. Use a production-only
Neon role and object-storage bucket; keep all Secret values out of Git.

The `network` Kustomize Component is supplied by the shared Kubernetes component
work and is included here so its policies are prerequisites for both app and
migration applies.
