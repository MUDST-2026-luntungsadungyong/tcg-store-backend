# SCRUM-84: Actual card photographs and listing rules

Date: 2026-11-05 (Asia/Bangkok)
Decision owner: Codex. The user delegated the unresolved design choices to Codex.
Scope: US-20, Neon/PostgreSQL, backend and frontend repositories.

## Decisions and reasons

- Catalog data is authoritative for card identity, rarity and official artwork. The seller supplies the physical copy condition, asking price, actual photographs and grading details. Catalog data cannot establish the condition or grading of a particular physical copy.
- Require actual front and back photographs when price is at least THB500, condition is not NM, a grading company is supplied, or the catalog rarity is outside the recognized low-rarity allowlist for that game. Unknown/null rarity requires photographs rather than silently bypassing the rule. Explicit mappings are kept with the policy implementation and must be covered by tests.
- Grading company, grade and certification number are seller declarations, not externally verified certifications. No fabricated default grade/company/certificate may be displayed.
- HIDE listings can be saved incomplete. Creating or updating a SHOW listing, or switching visibility to SHOW, re-evaluates the rule on the server. The preflight validation endpoint improves feedback but does not authorize a later write.
- Existing published listings remain readable after migration. Subsequent publication or edits must satisfy the current rule; migration does not hide existing stock or fabricate actual photographs.
- Front and back are explicit roles, with up to four additional photographs. Six photographs at 5MiB each need a total multipart allowance exceeding 30MiB. Keep the existing single cover image contract for older consumers while exposing actual photographs separately.
- Actual photograph URLs must originate from accepted uploads. Supplying an arbitrary URL or another listing's managed URL does not establish ownership or satisfy the rule. Image replacement retains storage rollback and after-commit deletion behavior.
- Actual photographs belong to a seller listing, not to the shared catalog card. Buyers must see the selected seller's physical copy; official artwork remains separately available. Magnification is 2.5x with an accessible touch/keyboard alternative.
- Add a new Flyway migration without modifying applied migrations. Nullable additive fields preserve existing rows. The target database is Neon PostgreSQL; TiDB is not a target for this delivery and must not be claimed as migrated or validated.
- Backend rules/API/storage tests establish authoritative enforcement. Frontend unit/component tests and Cypress journeys establish form behavior, API serialization and seller-specific gallery behavior. Mock journeys alone are not proof of an actual storage upload.

## Release considerations

The branches are feat/scrum-84-card-photo-policy in both repositories, created from their current origin/main. An unrelated pre-existing backend scripts/start-neon-local.ps1 edit is excluded from this change.

Read-only preflight found Neon Flyway history at V9, so V10 must be considered along with the new migration. V10 converts legacy PAID orders to CONFIRMED; it is an existing migration and is not rewritten for this feature.

The configured GitHub deployment runner was offline and backend GitHub Environments were absent at preflight. Repository admin access and database ownership do not prove the release pipeline is operational. Verification and release results are recorded separately; do not infer deployment from a successful build.

## Validation record

- Final backend tests: 266 total, zero failures/errors, two existing environment-dependent skips. PostgreSQL checkout concurrency was also executed separately: one passing test.
- Final controller regression: 37 passing, including blank grading, owned photo retention and missing-catalog-art removal. Packaging was rerun after stopping the test JVM that held the Windows jar open.
- Flyway PostgreSQL 16.15 local rehearsal passed: fresh V1-V11, repeat/no-op, V9-V11 upgrade, legacy listing preservation, V10 status conversion and DB constraints.
- A transactional preservation guard was rehearsed locally: deliberate unexpected data change rolled back both V10 and V11; unchanged data then migrated and validated successfully.
- Real HTTP API smoke used an isolated local database and the configured object storage: upload, public read, replace, hidden edit, publication rejection and deletion passed; all five unique test objects and disposable local records were removed. Neon was not used by this smoke.
- Read-only Neon readiness found baseline V9, V10/V11 pending, 52 listings, no legacy PAID orders and no conflicting new columns/table. No personal/order records were exported. An attempted full snapshot export was rejected by automatic approval review and was not performed.
- Neon application and GitHub CI outcomes are recorded in the release evidence after execution. An offline deployment runner and missing configured GitHub Environments remain deployment prerequisites; this implementation does not create them or claim a deployed application.
