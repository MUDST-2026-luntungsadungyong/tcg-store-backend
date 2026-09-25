-- TiDB Revision 5 schema migration (DDL only).
--
-- The existing tcg_store_test database uses the legacy cards/products model
-- required by the current backend.  Build the documented Revision 5 model in
-- a separate database so the legacy test environment remains a safe rollback.
-- Apply this file before 002_revision5_data.sql.

CREATE DATABASE IF NOT EXISTS tcg_store_revision5
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_bin;

USE tcg_store_revision5;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    contact_line VARCHAR(100),
    contact_facebook VARCHAR(255),
    avatar_url VARCHAR(500),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    seller_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_role_seller_status (role, seller_status),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_users_seller_status CHECK (seller_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS seller_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    shop_name VARCHAR(255) NOT NULL,
    shop_description TEXT,
    id_card_number VARCHAR(50),
    id_card_image_url VARCHAR(500),
    bank_name VARCHAR(100),
    bank_account_number VARCHAR(100),
    bank_account_name VARCHAR(255),
    requested_at DATETIME(6),
    verified_at DATETIME(6),
    approved_by BIGINT,
    rejected_at DATETIME(6),
    rejected_by BIGINT,
    rejection_reason TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_seller_profiles_user (user_id),
    KEY idx_seller_profiles_user (user_id),
    CONSTRAINT fk_seller_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_seller_profiles_approved_by FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_seller_profiles_rejected_by FOREIGN KEY (rejected_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    details JSON,
    ip_address VARCHAR(45),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_logs_actor (actor_id),
    KEY idx_audit_logs_action (action),
    KEY idx_audit_logs_created_at (created_at),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS global_cards (
    id VARCHAR(100) NOT NULL,
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
    market_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_admin_id BIGINT,
    updated_by_admin_id BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_global_cards_name (name),
    KEY idx_global_cards_set_rarity (set_name, rarity),
    KEY idx_global_cards_active (is_active),
    CONSTRAINT fk_global_cards_created_by FOREIGN KEY (created_by_admin_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_global_cards_updated_by FOREIGN KEY (updated_by_admin_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS local_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    seller_id BIGINT NOT NULL,
    global_card_id VARCHAR(100) NOT NULL,
    custom_image_url VARCHAR(500) NOT NULL,
    `condition` VARCHAR(50) NOT NULL,
    language VARCHAR(50) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    cost DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    on_hand INT NOT NULL DEFAULT 1,
    reserved INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'SHOW',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_local_cards_seller (seller_id),
    KEY idx_local_cards_global_card (global_card_id),
    KEY idx_local_cards_status_price (status, price),
    CONSTRAINT chk_local_cards_condition CHECK (`condition` IN ('NM', 'LP', 'MP', 'HP', 'DMG')),
    CONSTRAINT chk_local_cards_language CHECK (language IN ('EN', 'JP', 'TH')),
    CONSTRAINT chk_local_cards_price CHECK (price > 0),
    CONSTRAINT chk_local_cards_stock CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand),
    CONSTRAINT chk_local_cards_status CHECK (status IN ('SHOW', 'HIDE', 'OUT_OF_STOCK')),
    CONSTRAINT fk_local_cards_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_local_cards_global_card FOREIGN KEY (global_card_id) REFERENCES global_cards(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    local_card_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cart_user_local_card (user_id, local_card_id),
    CONSTRAINT chk_cart_quantity CHECK (quantity > 0),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_local_card FOREIGN KEY (local_card_id) REFERENCES local_cards(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL,
    order_number VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(12,2) NOT NULL,
    platform_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    seller_net_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_order_number (order_number),
    UNIQUE KEY uq_orders_idempotency_key (idempotency_key),
    KEY idx_orders_buyer (buyer_id),
    KEY idx_orders_status (status),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'PAID', 'FULFILLED', 'CANCELLED')),
    CONSTRAINT chk_orders_total CHECK (total_amount >= 0),
    CONSTRAINT chk_orders_platform_fee CHECK (platform_fee >= 0),
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    local_card_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    global_card_id VARCHAR(100) NOT NULL,
    card_name_snapshot VARCHAR(255) NOT NULL,
    shop_name_snapshot VARCHAR(255) NOT NULL,
    price_at_purchase DECIMAL(12,2) NOT NULL,
    cost_at_purchase DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order (order_id),
    KEY idx_order_items_seller (seller_id),
    CONSTRAINT chk_order_items_price CHECK (price_at_purchase >= 0),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_local_card FOREIGN KEY (local_card_id) REFERENCES local_cards(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS order_status_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    changed_by BIGINT,
    changed_by_role VARCHAR(30) NOT NULL,
    note TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_order_status_logs_order_created (order_id, created_at),
    CONSTRAINT chk_order_status_logs_role CHECK (changed_by_role IN ('SYSTEM', 'ADMIN', 'SELLER', 'BUYER')),
    CONSTRAINT fk_order_status_logs_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_status_logs_changed_by FOREIGN KEY (changed_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS collection_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    global_card_id VARCHAR(100) NOT NULL,
    `condition` VARCHAR(50) NOT NULL,
    language VARCHAR(50) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_collection_user_card (user_id, global_card_id, `condition`, language),
    KEY idx_collection_user (user_id),
    CONSTRAINT chk_collection_quantity CHECK (quantity > 0),
    CONSTRAINT fk_collection_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_collection_global_card FOREIGN KEY (global_card_id) REFERENCES global_cards(id) ON DELETE RESTRICT
);
