# TiDB Revision 5 schema reconciliation

`tcg-store-document/docs/DATABASE_DESIGN.md` defines the Revision 5 model as
ten tables centered on `global_cards` and `local_cards`. The TiDB test database
created by PR24 was intentionally based on the running JPA model and used the
legacy `cards` and `products` names.

## Mapping

| Legacy `tcg_store_test` | Revision 5 `tcg_store_revision5` | Transformation |
| --- | --- | --- |
| `users` | `users` | Copy; normalize role/status and required name. |
| `seller_profiles` | `seller_profiles` | Copy shared fields; KYC-only fields remain nullable because legacy fixtures have no KYC values. |
| `cards` | `global_cards` | Rename concept; add active/audit timestamps and required catalog fallbacks. |
| `products` | `local_cards` | Rename concept; map `card_id`, normalize condition/language/status, and fill missing image/cost. |
| `cart_items.product_id` | `cart_items.local_card_id` | Copy foreign key to the migrated listing. |
| `orders` | `orders` | Copy with Revision 5 financial columns and generated legacy idempotency keys. |
| `order_items.product_id/card_id` | `order_items.local_card_id/global_card_id` | Copy immutable card/listing snapshot fields and seller shop name. |
| `collection_items.card_id` | `collection_items.global_card_id` | Copy with normalized condition/language and the Revision 5 composite key. |
| *(none)* | `audit_logs`, `order_status_logs` | Create empty tables for the new platform audit/timeline capabilities. |

## TiDB verification

The migration was executed against the provisioned Starter instance on
2026-09-25. The source database remained unchanged.

- All ten Revision 5 tables were created.
- Row counts matched for all eight legacy tables with data mappings.
- Source and target order totals both returned `0.00`.
- Orphan checks for local-card sellers, local-card catalog references,
  collection-card references, and order-item orders all returned `0`.

The runtime profile and example environment now target `tcg_store_revision5`.
The application entity cutover remains a separate Java change: until the entity
annotations use the Revision 5 table and foreign-key names, Hibernate validation
must fail rather than silently falling back to the legacy `cards/products`
model.

## Application validation contract

The repository contains two bounded validation layers:

- `TidbRevision5SchemaContractTests` checks the immutable DDL/DML target,
  secret-free example environment, and critical JPA table/column/unique-key
  mappings. H2's `create-drop` mode cannot replace these checks.
- `TidbRevision5LiveValidationTests` is skipped unless
  `TIDB_REVISION5_VALIDATION=true`. The manual workflow
  `.github/workflows/tidb-revision5-validation.yml` supplies
  `TIDB_JDBC_URL`, `TIDB_USER`, and `TIDB_PASSWORD` through encrypted CI
  secrets and verifies the live target tables, columns, and foreign-key
  orphan counts.

The Java entity cutover must make the contract test pass before enabling the
live workflow as a required deployment gate.
