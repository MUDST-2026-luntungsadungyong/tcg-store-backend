-- TiDB Revision 5 data migration (DML only).
-- Copy the current legacy test fixtures into the Revision 5 database.
-- Run after 001_revision5_schema.sql. The source database is not modified.

USE tcg_store_revision5;

INSERT INTO users (
    id, email, password, full_name, phone, contact_line, contact_facebook,
    avatar_url, role, seller_status, created_at, updated_at
)
SELECT
    id, email, password, COALESCE(full_name, ''), LEFT(phone, 50), LEFT(contact_line, 100),
    LEFT(contact_facebook, 255), LEFT(avatar_url, 500),
    CASE WHEN role = 'ADMIN' THEN 'ADMIN' ELSE 'USER' END,
    CASE WHEN seller_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED') THEN seller_status ELSE 'NONE' END,
    created_at, updated_at
FROM tcg_store_test.users
ON DUPLICATE KEY UPDATE
    email = VALUES(email),
    password = VALUES(password),
    full_name = VALUES(full_name),
    phone = VALUES(phone),
    contact_line = VALUES(contact_line),
    contact_facebook = VALUES(contact_facebook),
    avatar_url = VALUES(avatar_url),
    role = VALUES(role),
    seller_status = VALUES(seller_status),
    updated_at = VALUES(updated_at);

INSERT INTO seller_profiles (
    id, user_id, shop_name, shop_description, verified_at, created_at, updated_at
)
SELECT
    id, user_id, shop_name, shop_description, verified_at, created_at, updated_at
FROM tcg_store_test.seller_profiles
ON DUPLICATE KEY UPDATE
    shop_name = VALUES(shop_name),
    shop_description = VALUES(shop_description),
    verified_at = VALUES(verified_at),
    updated_at = VALUES(updated_at);

INSERT INTO global_cards (
    id, name, supertype, subtypes, hp, types, rarity, set_name, card_number,
    artist, image_small, image_large, market_price, created_at, updated_at
)
SELECT
    c.id,
    c.name,
    COALESCE(NULLIF(c.supertype, ''), 'Unknown'),
    LEFT(c.subtypes, 100),
    LEFT(c.hp, 10),
    LEFT(c.types, 50),
    LEFT(c.rarity, 100),
    COALESCE(NULLIF(c.set_name, ''), 'Unknown Set'),
    COALESCE(NULLIF(c.card_number, ''), c.id),
    c.artist,
    COALESCE(NULLIF(c.image_small, ''), c.image_large, 'https://placehold.co/500x700'),
    COALESCE(NULLIF(c.image_large, ''), c.image_small, 'https://placehold.co/1000x1400'),
    COALESCE(c.market_price, 0.00),
    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tcg_store_test.cards c
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    supertype = VALUES(supertype),
    set_name = VALUES(set_name),
    card_number = VALUES(card_number),
    image_small = VALUES(image_small),
    image_large = VALUES(image_large),
    market_price = VALUES(market_price),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO local_cards (
    id, seller_id, global_card_id, custom_image_url, `condition`, language,
    price, cost, on_hand, reserved, status, created_at, updated_at
)
SELECT
    p.id,
    p.seller_id,
    p.card_id,
    COALESCE(NULLIF(p.custom_image_url, ''), c.image_large, c.image_small, 'https://placehold.co/1000x1400'),
    CASE UPPER(TRIM(p.`condition`))
        WHEN 'NM' THEN 'NM'
        WHEN 'NEAR MINT' THEN 'NM'
        WHEN 'LP' THEN 'LP'
        WHEN 'LIGHTLY PLAYED' THEN 'LP'
        WHEN 'MP' THEN 'MP'
        WHEN 'MODERATELY PLAYED' THEN 'MP'
        WHEN 'HP' THEN 'HP'
        WHEN 'HEAVILY PLAYED' THEN 'HP'
        WHEN 'DMG' THEN 'DMG'
        WHEN 'DAMAGED' THEN 'DMG'
        ELSE 'NM'
    END,
    CASE UPPER(TRIM(p.language)) WHEN 'JP' THEN 'JP' WHEN 'TH' THEN 'TH' ELSE 'EN' END,
    p.price,
    COALESCE(p.cost, 0.00),
    p.on_hand,
    p.reserved,
    CASE WHEN p.status IN ('SHOW', 'HIDE', 'OUT_OF_STOCK') THEN p.status ELSE 'HIDE' END,
    p.created_at, p.updated_at
FROM tcg_store_test.products p
JOIN tcg_store_revision5.global_cards c ON c.id = p.card_id
ON DUPLICATE KEY UPDATE
    seller_id = VALUES(seller_id),
    global_card_id = VALUES(global_card_id),
    custom_image_url = VALUES(custom_image_url),
    `condition` = VALUES(`condition`),
    language = VALUES(language),
    price = VALUES(price),
    cost = VALUES(cost),
    on_hand = VALUES(on_hand),
    reserved = VALUES(reserved),
    status = VALUES(status),
    updated_at = VALUES(updated_at);

INSERT INTO cart_items (id, user_id, local_card_id, quantity, created_at, updated_at)
SELECT ci.id, ci.user_id, ci.product_id, ci.quantity, ci.created_at, ci.updated_at
FROM tcg_store_test.cart_items ci
JOIN tcg_store_revision5.local_cards lc ON lc.id = ci.product_id
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), updated_at = VALUES(updated_at);

INSERT INTO orders (
    id, buyer_id, order_number, idempotency_key, status, total_amount,
    platform_fee, seller_net_amount, created_at, updated_at
)
SELECT
    o.id,
    o.buyer_id,
    o.order_number,
    CONCAT('legacy-order-', o.id),
    CASE WHEN o.status IN ('PENDING', 'PAID', 'FULFILLED', 'CANCELLED') THEN o.status ELSE 'PENDING' END,
    o.total_amount,
    0.00,
    o.total_amount,
    o.created_at,
    o.updated_at
FROM tcg_store_test.orders o
ON DUPLICATE KEY UPDATE
    buyer_id = VALUES(buyer_id),
    order_number = VALUES(order_number),
    status = VALUES(status),
    total_amount = VALUES(total_amount),
    updated_at = VALUES(updated_at);

INSERT INTO order_items (
    id, order_id, local_card_id, seller_id, global_card_id, card_name_snapshot,
    shop_name_snapshot, price_at_purchase, cost_at_purchase, quantity
)
SELECT
    oi.id,
    oi.order_id,
    oi.product_id,
    oi.seller_id,
    oi.card_id,
    oi.product_name_snapshot,
    COALESCE(sp.shop_name, ''),
    oi.price_at_purchase,
    COALESCE(oi.cost_at_purchase, 0.00),
    oi.quantity
FROM tcg_store_test.order_items oi
JOIN tcg_store_revision5.orders o ON o.id = oi.order_id
JOIN tcg_store_revision5.local_cards lc ON lc.id = oi.product_id
LEFT JOIN tcg_store_test.seller_profiles sp ON sp.user_id = oi.seller_id
ON DUPLICATE KEY UPDATE
    local_card_id = VALUES(local_card_id),
    seller_id = VALUES(seller_id),
    global_card_id = VALUES(global_card_id),
    card_name_snapshot = VALUES(card_name_snapshot),
    shop_name_snapshot = VALUES(shop_name_snapshot),
    price_at_purchase = VALUES(price_at_purchase),
    cost_at_purchase = VALUES(cost_at_purchase),
    quantity = VALUES(quantity);

INSERT INTO collection_items (
    id, user_id, global_card_id, `condition`, language, quantity, created_at, updated_at
)
SELECT
    ci.id,
    ci.user_id,
    ci.card_id,
    COALESCE(NULLIF(TRIM(ci.`condition`), ''), 'NM'),
    CASE UPPER(TRIM(ci.language)) WHEN 'JP' THEN 'JP' WHEN 'TH' THEN 'TH' ELSE 'EN' END,
    ci.quantity,
    ci.created_at,
    ci.updated_at
FROM tcg_store_test.collection_items ci
JOIN tcg_store_revision5.global_cards gc ON gc.id = ci.card_id
ON DUPLICATE KEY UPDATE
    quantity = VALUES(quantity),
    updated_at = VALUES(updated_at);
