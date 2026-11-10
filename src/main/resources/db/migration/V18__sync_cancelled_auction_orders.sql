-- Older order cancellations returned stock without closing the linked auction.
UPDATE auctions
SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
WHERE status IN ('AWAITING_BUYER_CONTACT', 'BUYOUT_COMPLETED')
  AND EXISTS (
      SELECT 1 FROM orders
      WHERE orders.id = auctions.order_id
        AND orders.order_type = 'AUCTION'
        AND orders.status = 'CANCELLED'
  );
