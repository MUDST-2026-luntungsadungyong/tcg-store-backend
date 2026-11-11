# Notification email

In-app notifications are written with the business transaction. Selected notification types also create a row in `notification_email_outbox` in that transaction. A scheduled worker claims due rows, sends plain-text email, and retries failures with increasing delay. The unique `notification_id` prevents the same notification from being queued twice. SMTP can still deliver a duplicate if the process stops after the provider accepts a message but before `sent_at` is recorded.

Apply Flyway migrations V24–V26 before starting the updated API. V24 extends the current notification type constraint while preserving the auction and seller application types from V15–V23. V25 creates the email outbox. V26 adds order tracking and carrier columns. For the separate TiDB migration path, apply `005_order_shipping_and_notification_email.sql` after migrations 001–004.

Set `APP_MAIL_ENABLED`, `APP_MAIL_FROM`, `APP_PUBLIC_BASE_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, and `SMTP_PASSWORD`. Set `SMTP_AUTH` and `SMTP_STARTTLS` to match the provider. Keep `APP_MAIL_ENABLED=false` until the updated application and migrations are released and the queued outbox rows have been reviewed. While disabled, new selected notifications remain queued.

On AKS, keep keys `SMTP_USERNAME` and `SMTP_PASSWORD` in the `tcg-store-mail-secrets` Kubernetes Secret in the `tcg-store-aks-demo` namespace. The backend Deployment references only those named keys. The other mail settings are in the overlay ConfigMap. Create the Secret before deploying the backend to a new cluster. For Brevo, authorize the AKS outbound public IP under **Settings > Security > Authorized IPs** if SMTP authentication returns `525 5.7.1 Unauthorized IP address`. Verify SMTP authentication and the sender domain before enabling mail; enabling mail sends pending messages.

Email is sent for order reservations and placement, order cancellation, dispatch, seller application decisions, auction cancellation decisions, outbid and win events, actionable seller auction end, second chance, and default warnings. Confirmation and receipt acknowledgement remain in the web inbox only. Admin resolution of auction shipment is excluded from email notifications.

Dispatch emails include the order tracking number and optional carrier. Marking an order as sent requires a nonblank tracking number. This validates presence, not whether the carrier recognizes the number.

## Password recovery

Apply V27 before deploying this release (or TiDB migration 006 on the separate TiDB path). Configure a stable `APP_JWT_SECRET`, `APP_PUBLIC_BASE_URL` and the SMTP settings above. The public base URL is the trusted origin used to build reset links; never derive it from incoming request headers. All API replicas must use the same JWT secret. Rotating that secret invalidates existing login tokens and any queued encrypted recovery emails.

Password recovery requests always return 204 for a syntactically valid email. An existing account is limited to one recovery email per minute. Links expire after 30 minutes, a newer request invalidates its predecessor, and a successful reset invalidates existing JWT sessions. The database holds a SHA-256 token hash; the queued email body is encrypted with an authenticated key derived separately from the JWT secret, and is erased after sending or expiry. Expired recovery emails are discarded without delivery. The worker may retry transient SMTP failures, subject to the link expiry. Configure edge-level request throttling as well, especially for unknown accounts and distributed clients.

`APP_MAIL_ENABLED` still gates the entire notification queue. Keep it disabled until SMTP credentials and sender identity are verified and existing queued notification mail has been reviewed; enabling it starts sending eligible queued mail. If mail is disabled or unavailable, recovery links can expire without delivery, and the user needs to request a fresh link after service is restored.
