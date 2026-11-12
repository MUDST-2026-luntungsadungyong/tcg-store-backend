ALTER TABLE auctions ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0);
ALTER TABLE order_items ALTER COLUMN local_card_id DROP NOT NULL;
ALTER TABLE auctions ADD COLUMN language VARCHAR(10) NOT NULL DEFAULT 'EN';
UPDATE auctions SET language = COALESCE((SELECT language FROM local_cards WHERE local_cards.id = auctions.product_id), 'EN');

ALTER TABLE auctions DROP CONSTRAINT chk_auctions_hold;
ALTER TABLE auctions ADD CONSTRAINT chk_auctions_hold CHECK (
    (NOT stock_held OR product_id IS NOT NULL)
    AND (status NOT IN ('SCHEDULED', 'ACTIVE') OR product_id IS NULL OR stock_held)
);
