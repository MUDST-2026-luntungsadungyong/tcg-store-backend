-- Replace auction payment-proof settlement with direct buyer-to-seller contact.
-- Payment-proof storage is removed because the application no longer accepts or reads slips.

ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_other VARCHAR(255);

DROP TABLE IF EXISTS auction_payment_proofs;
ALTER TABLE auctions DROP COLUMN IF EXISTS payment_deadline;
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING', 'CONFIRMED', 'AWAITING_BUYER_CONTACT',
                      'PENDING_SELLER_ACTION', 'SENT', 'FULFILLED', 'CANCELLED', 'DEFAULTED'));

-- Keep in-flight auction orders usable after deploying the new state machine.
UPDATE orders
SET status = 'AWAITING_BUYER_CONTACT'
WHERE status = 'PENDING'
  AND id IN (
      SELECT order_id
      FROM auctions
      WHERE order_id IS NOT NULL
        AND status IN ('AWAITING_PAYMENT', 'BUYOUT_COMPLETED')
  );

ALTER TABLE auctions DROP CONSTRAINT IF EXISTS auctions_status_check;
UPDATE auctions
SET status = 'AWAITING_BUYER_CONTACT'
WHERE status IN ('AWAITING_PAYMENT', 'BUYOUT_COMPLETED');
ALTER TABLE auctions ADD CONSTRAINT auctions_status_check CHECK (status IN (
    'DRAFT', 'SCHEDULED', 'ACTIVE', 'ENDED', 'AWAITING_BUYER_CONTACT', 'COMPLETED', 'CANCELLED',
    'BUYOUT_COMPLETED', 'RESERVE_NOT_MET', 'UNSOLD_NO_BIDS', 'DEFAULTED'
));
