ALTER TABLE notification_email_outbox
    ADD COLUMN attachment_order_id BIGINT,
    ADD COLUMN attachment_type VARCHAR(100),
    ADD COLUMN attachment_status_snapshot VARCHAR(40),
    ADD COLUMN cancelled_at TIMESTAMP,
    ADD COLUMN cancel_reason VARCHAR(255),
    ADD COLUMN failed_at TIMESTAMP,
    ADD COLUMN failure_reason VARCHAR(500);

-- Keep pre-release outbox rows for audit and controlled review, but prevent stale
-- or historically ambiguous rows (including dispute messages stored as
-- AUCTION_CANCELLATION_DECIDED) from being sent on a later mail enablement.
UPDATE notification_email_outbox
SET cancelled_at = CURRENT_TIMESTAMP,
    cancel_reason = 'Held for supervised email rollout'
WHERE sent_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX idx_notification_email_active_due
    ON notification_email_outbox (cancelled_at, sent_at, next_attempt_at, claimed_until);
