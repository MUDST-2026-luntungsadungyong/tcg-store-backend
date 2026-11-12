CREATE TABLE stock_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    global_card_id VARCHAR(100) NOT NULL REFERENCES global_cards(id) ON DELETE CASCADE,
    subscribed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_subscription_buyer_card UNIQUE (buyer_id, global_card_id)
);

CREATE INDEX idx_stock_subscriptions_card ON stock_subscriptions(global_card_id, id);

CREATE TABLE seller_stock_alert_state (
    product_id BIGINT PRIMARY KEY REFERENCES local_cards(id) ON DELETE CASCADE,
    last_alert_level VARCHAR(20) NOT NULL CHECK (last_alert_level IN ('LOW', 'CRITICAL', 'OUT_OF_STOCK')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One transaction-held row lock makes polling work exclusive across application pods.
CREATE TABLE stock_notification_worker_lock (
    lock_key VARCHAR(20) PRIMARY KEY
);
INSERT INTO stock_notification_worker_lock(lock_key) VALUES ('MAIN');

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_notification_type_check;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (notification_type IN (
    'ORDER_RESERVED', 'ORDER_STATUS_CHANGED',
    'ORDER_PLACED_BUYER', 'ORDER_CONFIRMED_BUYER', 'ORDER_CANCELLED_BUYER',
    'ORDER_CANCELLED_SELLER', 'ORDER_FULFILLED_SELLER', 'ORDER_SENT_BUYER',
    'SELLER_APPLICATION_SUBMITTED', 'SELLER_APPLICATION_APPROVED', 'SELLER_APPLICATION_REJECTED',
    'AUCTION_CANCELLATION_SUBMITTED', 'AUCTION_CANCELLATION_DECIDED',
    'AUCTION_OUTBID', 'AUCTION_WON', 'AUCTION_ENDED_SELLER',
    'AUCTION_WATCHLIST_ENDING', 'AUCTION_SECOND_CHANCE', 'AUCTION_DEFAULTED_WARNING',
    'AUCTION_DISPUTE_OPENED', 'AUCTION_DISPUTE_EVIDENCE_ADDED', 'AUCTION_DISPUTE_DECIDED',
    'STOCK_AVAILABLE', 'SELLER_STOCK_LOW', 'SELLER_STOCK_CRITICAL', 'SELLER_OUT_OF_STOCK'
));
