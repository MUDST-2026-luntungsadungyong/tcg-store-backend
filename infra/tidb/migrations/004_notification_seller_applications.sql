ALTER TABLE notifications
    DROP FOREIGN KEY fk_notifications_order;

ALTER TABLE notifications
    MODIFY COLUMN order_id BIGINT NULL;

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_order
        FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE;

ALTER TABLE notifications
    ADD COLUMN seller_profile_id BIGINT NULL;

ALTER TABLE notifications
    DROP CONSTRAINT chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type CHECK (notification_type IN (
        'ORDER_RESERVED',
        'ORDER_STATUS_CHANGED',
        'SELLER_APPLICATION_SUBMITTED',
        'SELLER_APPLICATION_APPROVED',
        'SELLER_APPLICATION_REJECTED'
    ));

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_seller_profile
        FOREIGN KEY (seller_profile_id) REFERENCES seller_profiles(id) ON DELETE CASCADE;

CREATE INDEX idx_notifications_recipient_read
    ON notifications(recipient_id, read_at);
