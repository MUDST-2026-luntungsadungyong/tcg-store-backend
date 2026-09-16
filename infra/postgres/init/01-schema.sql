-- =============================================================================
-- Mythix TCG Platform — Production Schema Initialization (Revision 5)
-- Target DBMS: PostgreSQL 16
-- 10 Core Tables: users, seller_profiles, audit_logs, global_cards,
--                 local_cards, cart_items, orders, order_items,
--                 order_status_logs, collection_items
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Table: users (Core Identity & Auth)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone VARCHAR(50),
    contact_line VARCHAR(100),
    contact_facebook VARCHAR(255),
    avatar_url VARCHAR(500),
    role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    seller_status VARCHAR(20) NOT NULL DEFAULT 'NONE' CHECK (seller_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role_seller_status ON users(role, seller_status);

-- 2. Table: seller_profiles (Seller KYC Verification & Bank Payout)
CREATE TABLE IF NOT EXISTS seller_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_name VARCHAR(255) NOT NULL,
    shop_description TEXT,
    id_card_number VARCHAR(50),
    id_card_image_url VARCHAR(500),
    bank_name VARCHAR(100),
    bank_account_number VARCHAR(100),
    bank_account_name VARCHAR(255),
    requested_at TIMESTAMP,
    verified_at TIMESTAMP,
    approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    rejected_at TIMESTAMP,
    rejected_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    rejection_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_seller_profiles_user ON seller_profiles(user_id);

-- 3. Table: audit_logs (Security & Admin Activity Trail)
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    details JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor ON audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at DESC);

-- 4. Table: global_cards (Master Pokémon Catalog - Ownerless)
CREATE TABLE IF NOT EXISTS global_cards (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    supertype VARCHAR(50) NOT NULL,
    subtypes VARCHAR(100),
    hp VARCHAR(10),
    types VARCHAR(50),
    rarity VARCHAR(100),
    set_name VARCHAR(255) NOT NULL,
    card_number VARCHAR(50) NOT NULL,
    artist VARCHAR(255),
    image_small VARCHAR(500) NOT NULL,
    image_large VARCHAR(500) NOT NULL,
    market_price DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_admin_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    updated_by_admin_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_global_cards_name ON global_cards(name);
CREATE INDEX IF NOT EXISTS idx_global_cards_set_rarity ON global_cards(set_name, rarity);
CREATE INDEX IF NOT EXISTS idx_global_cards_active ON global_cards(is_active);

-- 5. Table: local_cards (Seller Marketplace Listings & Stock)
CREATE TABLE IF NOT EXISTS local_cards (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    global_card_id VARCHAR(100) NOT NULL REFERENCES global_cards(id) ON DELETE RESTRICT,
    custom_image_url VARCHAR(500) NOT NULL,
    condition VARCHAR(50) NOT NULL CHECK (condition IN ('NM', 'LP', 'MP', 'HP', 'DMG')),
    language VARCHAR(50) NOT NULL CHECK (language IN ('EN', 'JP', 'TH')),
    price DECIMAL(12, 2) NOT NULL CHECK (price > 0),
    cost DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    on_hand INT NOT NULL DEFAULT 1 CHECK (on_hand >= 0),
    reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    status VARCHAR(20) NOT NULL DEFAULT 'SHOW' CHECK (status IN ('SHOW', 'HIDE', 'OUT_OF_STOCK')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_local_cards_seller ON local_cards(seller_id);
CREATE INDEX IF NOT EXISTS idx_local_cards_global_card ON local_cards(global_card_id);
CREATE INDEX IF NOT EXISTS idx_local_cards_status_price ON local_cards(status, price);

-- 6. Table: cart_items (Shopping Cart)
CREATE TABLE IF NOT EXISTS cart_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    local_card_id BIGINT NOT NULL REFERENCES local_cards(id) ON DELETE CASCADE,
    quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cart_user_product UNIQUE (user_id, local_card_id)
);

CREATE INDEX IF NOT EXISTS idx_cart_user ON cart_items(user_id);

-- 7. Table: orders (Transactions & Orders)
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FULFILLED', 'CANCELLED')),
    total_amount DECIMAL(12, 2) NOT NULL CHECK (total_amount >= 0),
    platform_fee DECIMAL(12, 2) NOT NULL DEFAULT 0.00 CHECK (platform_fee >= 0),
    seller_net_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_orders_buyer ON orders(buyer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);

-- 8. Table: order_items (Immutable Purchase Snapshot)
CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    local_card_id BIGINT NOT NULL REFERENCES local_cards(id) ON DELETE RESTRICT,
    seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    global_card_id VARCHAR(100) NOT NULL,
    card_name_snapshot VARCHAR(255) NOT NULL,
    shop_name_snapshot VARCHAR(255) NOT NULL,
    price_at_purchase DECIMAL(12, 2) NOT NULL CHECK (price_at_purchase >= 0),
    cost_at_purchase DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    quantity INT NOT NULL CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_seller ON order_items(seller_id);

-- 9. Table: order_status_logs (Order Timeline Tracking)
CREATE TABLE IF NOT EXISTS order_status_logs (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    changed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    changed_by_role VARCHAR(30) NOT NULL CHECK (changed_by_role IN ('SYSTEM', 'ADMIN', 'SELLER', 'BUYER')),
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_status_logs_order ON order_status_logs(order_id, created_at ASC);

-- 10. Table: collection_items (Personal Binder)
CREATE TABLE IF NOT EXISTS collection_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    global_card_id VARCHAR(100) NOT NULL REFERENCES global_cards(id) ON DELETE RESTRICT,
    condition VARCHAR(50) NOT NULL CHECK (condition IN ('NM', 'LP', 'MP', 'HP', 'DMG')),
    language VARCHAR(50) NOT NULL CHECK (language IN ('EN', 'JP', 'TH')),
    quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_collection_user_card UNIQUE (user_id, global_card_id, condition, language)
);

CREATE INDEX IF NOT EXISTS idx_collection_user ON collection_items(user_id);
