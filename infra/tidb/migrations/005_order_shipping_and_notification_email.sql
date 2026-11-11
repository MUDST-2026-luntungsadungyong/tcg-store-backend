ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(120) NULL;
ALTER TABLE orders ADD COLUMN shipping_carrier VARCHAR(120) NULL;

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (notification_type IN (
    'ORDER_RESERVED', 'ORDER_STATUS_CHANGED', 'ORDER_PLACED_BUYER',
    'ORDER_CONFIRMED_BUYER', 'ORDER_CANCELLED_BUYER', 'ORDER_CANCELLED_SELLER',
    'ORDER_FULFILLED_SELLER', 'ORDER_SENT_BUYER',
    'SELLER_APPLICATION_SUBMITTED', 'SELLER_APPLICATION_APPROVED', 'SELLER_APPLICATION_REJECTED',
    'AUCTION_CANCELLATION_SUBMITTED', 'AUCTION_CANCELLATION_DECIDED',
    'AUCTION_OUTBID', 'AUCTION_WON', 'AUCTION_ENDED_SELLER',
    'AUCTION_WATCHLIST_ENDING', 'AUCTION_SECOND_CHANCE', 'AUCTION_DEFAULTED_WARNING'
));

CREATE TABLE IF NOT EXISTS notification_email_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    claimed_until DATETIME NULL,
    sent_at DATETIME NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notification_email_outbox_notification (notification_id),
    KEY idx_notification_email_due (sent_at, next_attempt_at, claimed_until)
);
