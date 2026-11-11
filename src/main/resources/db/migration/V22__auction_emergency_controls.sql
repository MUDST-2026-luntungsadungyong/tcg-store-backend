ALTER TABLE auctions ADD COLUMN paused_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE auctions DROP CONSTRAINT auctions_status_check;
ALTER TABLE auctions ADD CONSTRAINT auctions_status_check CHECK (status IN (
 'DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED', 'AWAITING_BUYER_CONTACT', 'COMPLETED', 'CANCELLED',
 'BUYOUT_COMPLETED', 'RESERVE_NOT_MET', 'UNSOLD_NO_BIDS', 'DEFAULTED'
));
ALTER TABLE auctions DROP CONSTRAINT chk_auctions_hold;
ALTER TABLE auctions ADD CONSTRAINT chk_auctions_hold CHECK (
 (NOT stock_held OR product_id IS NOT NULL)
 AND (status NOT IN ('SCHEDULED', 'ACTIVE', 'PAUSED') OR product_id IS NULL OR stock_held)
);
CREATE TABLE auction_disputes (
 id BIGSERIAL PRIMARY KEY, auction_id BIGINT NOT NULL UNIQUE REFERENCES auctions(id), order_id BIGINT NOT NULL REFERENCES orders(id),
 buyer_id BIGINT NOT NULL REFERENCES users(id), seller_id BIGINT NOT NULL REFERENCES users(id), reason VARCHAR(2000) NOT NULL,
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, decided_at TIMESTAMP WITH TIME ZONE,
 decision_reason VARCHAR(2000), decided_by BIGINT REFERENCES users(id)
);
CREATE TABLE auction_dispute_evidence (
 id BIGSERIAL PRIMARY KEY, dispute_id BIGINT NOT NULL REFERENCES auction_disputes(id), submitted_by BIGINT NOT NULL REFERENCES users(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL, content_type VARCHAR(100) NOT NULL, content BYTEA NOT NULL
);

ALTER TABLE auctions ADD COLUMN received_at TIMESTAMP WITH TIME ZONE;
