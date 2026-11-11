# Auction default review and participation penalties (US-17-6)

An auction seller can cancel an order awaiting buyer contact with a reason and the no-contact declaration. This releases the order reservation, marks the auction `DEFAULTED`, and creates a pending review case. It does not immediately penalize the buyer. The seller can continue the existing Second-Chance process independently of the review.

An administrator approves or rejects a pending case with a reason. Approved cases add a strike: the first is a warning, the second suspends auction participation for seven days, and the third and later suspensions last thirty days. Durations start at approval. Administrators can reverse an approved case with a reason. Reversal recomputes the remaining penalties at their original approval times and retains pre-existing penalties. Historical decisions remain in the audit trail. Repeated identical decisions do not add another strike.

Suspension blocks new manual bids, proxy bids, Buy-It-Now purchases and acceptance of Second-Chance offers. Existing proxy instructions cannot place another automatic bid while their owner is suspended. Existing committed bids and orders remain valid, and buyers can still complete orders and receive cards in their Binder.

There is no payment-proof upload, payment deadline, or automatic 48-hour default. A report is a seller declaration, not proof of nonpayment. Administrators should inspect the report and the order history before deciding.

## Database rollout

V20 is an additive migration: it creates review cases and decision history, and adds nullable baseline fields used to preserve existing user penalties. It does not create cases or strikes for historical auctions and does not change historical Flyway checksums.

1. Build the application artifact and run its tests. Rehearse both a fresh database and a V19-to-V20 upgrade on disposable PostgreSQL, including repeat Flyway validation and preservation of existing rows.
2. Perform a read-only target preflight. Verify the intended Neon project/database, direct TLS endpoint, successful Flyway history through V19, and absence of unexpected pending migrations. Use the configured secret source; do not put credentials in command arguments or logs.
3. Confirm the database recovery mechanism is available. The migration is transactional and additive; do not export production rows into development artifacts. Keep the previous application artifact available for rollback.
4. Use the packaged `DatabaseMigrationApplication` entry point with the `migration` profile, `spring.flyway.target=20`, and `spring.flyway.group=true`. Bound lock and statement timeouts. Keep Flyway clean disabled. Run migration before starting the new API.
5. Verify that exactly V20 was newly applied, previous history/checksums are unchanged, the new tables/columns/constraints exist, and JPA schema validation succeeds. Re-run validation to confirm no pending migrations.
6. Release backend before frontend. Smoke-test authorization, pending-case creation, administrative decisions, participant visibility, suspended participation and continued access to existing orders using dedicated test accounts.

If migration fails, investigate the failure and retry the same immutable migration after resolving the cause. Do not use Flyway repair or edit an applied migration to bypass validation. If the application needs rollback after V20 succeeds, retain the additive schema and deploy the previous application; do not drop cases, decision history or baseline columns. Any subsequent schema correction must use a new versioned migration.

## Operational checks

- Only administrators can decide cases; participants only see cases involving their account.
- A repeated report/decision must not create duplicate cases, decisions, strikes or notifications.
- A suspension ends when its UTC expiry is reached; an expired timestamp can remain stored as history.
- Reversing one case must not erase a separate active suspension or create a new suspension starting at reversal time.
- Review decisions and order fulfillment must not interfere with stock, Second-Chance reservations or Binder ownership.
