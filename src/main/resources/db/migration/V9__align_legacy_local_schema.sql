-- Flyway V9: upgrade legacy local Docker databases (cards/products naming) to the
-- current global_cards/local_cards model without dropping existing data.

DO $$
BEGIN
    IF to_regclass('public.global_cards') IS NULL
       AND to_regclass('public.cards') IS NOT NULL THEN
        ALTER TABLE cards RENAME TO global_cards;
    END IF;

    IF to_regclass('public.local_cards') IS NULL
       AND to_regclass('public.products') IS NOT NULL THEN
        ALTER TABLE products RENAME TO local_cards;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'local_cards' AND column_name = 'card_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'local_cards' AND column_name = 'global_card_id'
    ) THEN
        ALTER TABLE local_cards RENAME COLUMN card_id TO global_card_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'cart_items' AND column_name = 'product_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'cart_items' AND column_name = 'local_card_id'
    ) THEN
        ALTER TABLE cart_items RENAME COLUMN product_id TO local_card_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'collection_items' AND column_name = 'card_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'collection_items' AND column_name = 'global_card_id'
    ) THEN
        ALTER TABLE collection_items RENAME COLUMN card_id TO global_card_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'product_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'local_card_id'
    ) THEN
        ALTER TABLE order_items RENAME COLUMN product_id TO local_card_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'card_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'global_card_id'
    ) THEN
        ALTER TABLE order_items RENAME COLUMN card_id TO global_card_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'product_name_snapshot'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_items' AND column_name = 'card_name_snapshot'
    ) THEN
        ALTER TABLE order_items RENAME COLUMN product_name_snapshot TO card_name_snapshot;
    END IF;
END $$;

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);

ALTER TABLE global_cards
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS created_by_admin_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS updated_by_admin_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE global_cards
SET supertype = COALESCE(supertype, 'Unknown'),
    set_name = COALESCE(set_name, ''),
    card_number = COALESCE(card_number, ''),
    image_small = COALESCE(image_small, ''),
    image_large = COALESCE(image_large, ''),
    market_price = COALESCE(market_price, 0.00);

ALTER TABLE global_cards
    ALTER COLUMN supertype SET NOT NULL,
    ALTER COLUMN set_name SET NOT NULL,
    ALTER COLUMN card_number SET NOT NULL,
    ALTER COLUMN image_small SET NOT NULL,
    ALTER COLUMN image_large SET NOT NULL,
    ALTER COLUMN market_price SET DEFAULT 0.00,
    ALTER COLUMN market_price SET NOT NULL;

UPDATE local_cards AS local_card
SET custom_image_url = COALESCE(
    NULLIF(local_card.custom_image_url, ''),
    NULLIF(global_card.image_large, ''),
    NULLIF(global_card.image_small, ''),
    ''
)
FROM global_cards AS global_card
WHERE global_card.id = local_card.global_card_id
  AND (local_card.custom_image_url IS NULL OR local_card.custom_image_url = '');

UPDATE local_cards
SET custom_image_url = ''
WHERE custom_image_url IS NULL;

ALTER TABLE local_cards
    ALTER COLUMN custom_image_url SET NOT NULL;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS platform_fee DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS seller_net_amount DECIMAL(12, 2);

UPDATE orders
SET seller_net_amount = COALESCE(seller_net_amount, total_amount - platform_fee);

ALTER TABLE orders
    ALTER COLUMN seller_net_amount SET DEFAULT 0.00,
    ALTER COLUMN seller_net_amount SET NOT NULL;

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS shop_name_snapshot VARCHAR(255);

UPDATE order_items AS item
SET shop_name_snapshot = COALESCE(profile.shop_name, '')
FROM seller_profiles AS profile
WHERE profile.user_id = item.seller_id
  AND item.shop_name_snapshot IS NULL;

UPDATE order_items
SET shop_name_snapshot = ''
WHERE shop_name_snapshot IS NULL;

ALTER TABLE order_items
    ALTER COLUMN shop_name_snapshot SET NOT NULL;

CREATE TABLE IF NOT EXISTS order_status_logs (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    changed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    changed_by_role VARCHAR(30) NOT NULL
        CHECK (changed_by_role IN ('SYSTEM', 'ADMIN', 'SELLER', 'BUYER')),
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_global_cards_name ON global_cards(name);
CREATE INDEX IF NOT EXISTS idx_global_cards_set_rarity ON global_cards(set_name, rarity);
CREATE INDEX IF NOT EXISTS idx_global_cards_active ON global_cards(is_active);
CREATE INDEX IF NOT EXISTS idx_local_cards_seller ON local_cards(seller_id);
CREATE INDEX IF NOT EXISTS idx_local_cards_global_card ON local_cards(global_card_id);
CREATE INDEX IF NOT EXISTS idx_local_cards_status_price ON local_cards(status, price);
CREATE INDEX IF NOT EXISTS idx_order_status_logs_order
    ON order_status_logs(order_id, created_at ASC);
