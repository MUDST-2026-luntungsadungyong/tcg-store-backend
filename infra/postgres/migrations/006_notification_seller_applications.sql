ALTER TABLE notifications
    ALTER COLUMN order_id DROP NOT NULL;

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS seller_profile_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_notifications_seller_profile'
    ) THEN
        ALTER TABLE notifications
            ADD CONSTRAINT fk_notifications_seller_profile
                FOREIGN KEY (seller_profile_id) REFERENCES seller_profiles(id) ON DELETE CASCADE;
    END IF;
END $$;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS notifications_notification_type_check;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type CHECK (notification_type IN (
        'ORDER_RESERVED',
        'ORDER_STATUS_CHANGED',
        'SELLER_APPLICATION_SUBMITTED',
        'SELLER_APPLICATION_APPROVED',
        'SELLER_APPLICATION_REJECTED'
    ));

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_read
    ON notifications(recipient_id, read_at);
