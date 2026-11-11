# Notification email

In-app notifications are written with the business transaction. Selected notification types also create a row in `notification_email_outbox` in that transaction. A scheduled worker claims due rows, sends MIME email, and retries failures with increasing delay. The unique `notification_id` prevents the same notification from being queued twice. SMTP can still deliver a duplicate if the process stops after the provider accepts a message but before `sent_at` is recorded.

Apply Flyway migrations V24–V28 before starting the updated API. V27 adds the three dispute notification types while preserving existing types. V28 adds attachment snapshot and cancellation metadata, and suppresses every unsent pre-release outbox row in place. No rows are deleted. V28 only catches rows present at migration time, so after the updated API is running and before mail is enabled, choose a rollout cutoff timestamp and hold any remaining unsent rows queued before it:

```sql
UPDATE notification_email_outbox
SET cancelled_at = CURRENT_TIMESTAMP,
    cancel_reason = 'Held before supervised email rollout'
WHERE sent_at IS NULL AND cancelled_at IS NULL AND next_attempt_at < TIMESTAMP '2026-11-12 00:00:00';
```

Replace the example timestamp with the actual supervised rollout cutoff. Review held rows by notification ID before intentionally clearing `cancelled_at` for any message approved for delivery. For the separate TiDB migration path, apply `005_order_shipping_and_notification_email.sql` after migrations 001–004; email attachment delivery is currently supported on the PostgreSQL/Flyway path only.

Set `APP_MAIL_ENABLED`, `APP_MAIL_FROM`, `APP_PUBLIC_BASE_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, and `SMTP_PASSWORD`. Set `SMTP_AUTH` and `SMTP_STARTTLS` to match the provider. Keep `APP_MAIL_ENABLED=false` in manifests until supervised rollout. Existing unsent rows are cancelled by V28 and cannot be picked up accidentally; new selected notifications remain queued while mail is disabled. To release a reviewed backlog item, inspect its notification and recipient, explicitly clear `cancelled_at` and `cancel_reason` for that outbox ID, then enable delivery under supervision. Do not bulk-clear the cancellation fields.

On AKS, keep keys `SMTP_USERNAME` and `SMTP_PASSWORD` in the `tcg-store-mail-secrets` Kubernetes Secret in the `tcg-store-aks-demo` namespace. The backend Deployment references only those named keys. The other mail settings are in the overlay ConfigMap. Create the Secret before deploying the backend to a new cluster. For Brevo, authorize the AKS outbound public IP under **Settings > Security > Authorized IPs** if SMTP authentication returns `525 5.7.1 Unauthorized IP address`. Verify SMTP authentication and the sender domain before enabling mail; enabling mail sends pending messages.

Email is sent for order reservations and placement, order cancellation, dispatch, seller application decisions, auction cancellation decisions, outbid and win events, actionable seller auction end, second chance, default warnings, dispute opened, and dispute decided. Evidence-added and routine events remain web-only. Dispute notices use their own notification types and `/auction-disputes?disputeId={id}` action path, avoiding the older auction cancellation type collision.

Dispatch emails include the order tracking number, optional carrier, order detail link, and a generated `mythix-order-{orderNumber}.pdf` Order Summary. The outbox stores only the order ID, `application/pdf` type, and `SENT` status snapshot; the delivery worker loads the order with its buyer and item snapshots and regenerates the PDF for each retry. Its QR code points to `${APP_PUBLIC_BASE_URL}/orders/verify/{orderNumber}` (production: `https://tcgmythix.store/orders/verify/{orderNumber}`). The PDF is never persisted. Invalid attachment metadata, a missing source order, or a generated attachment over 5 MiB is recorded as a terminal outbox failure and is not sent without its required attachment. Marking an order as sent requires a nonblank tracking number. This validates presence, not whether the carrier recognizes the number.
