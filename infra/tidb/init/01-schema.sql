-- TiDB Cloud Starter test schema.
-- Keep this schema aligned with the JPA entities. Run it once before starting
-- the backend with SPRING_PROFILES_ACTIVE=tidb.

CREATE DATABASE IF NOT EXISTS tcg_store_test
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_bin;

USE tcg_store_test;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone VARCHAR(255),
    contact_line VARCHAR(255),
    contact_facebook VARCHAR(255),
    avatar_url VARCHAR(255),
    role VARCHAR(255) NOT NULL DEFAULT 'USER',
    seller_status VARCHAR(255) NOT NULL DEFAULT 'NONE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
);

CREATE TABLE IF NOT EXISTS seller_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    shop_name VARCHAR(255) NOT NULL,
    shop_description TEXT,
    contact_phone VARCHAR(255),
    contact_line VARCHAR(255),
    contact_facebook VARCHAR(255),
    verified_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_seller_profiles_user (user_id),
    CONSTRAINT fk_seller_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cards (
    id VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    supertype VARCHAR(255),
    subtypes VARCHAR(255),
    hp VARCHAR(255),
    types VARCHAR(255),
    rarity VARCHAR(255),
    set_name VARCHAR(255),
    card_number VARCHAR(255),
    artist VARCHAR(255),
    image_small VARCHAR(500),
    image_large VARCHAR(500),
    market_price DECIMAL(12,2) DEFAULT 0.00,
    PRIMARY KEY (id),
    KEY idx_cards_name (name),
    KEY idx_cards_rarity (rarity)
);

CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    seller_id BIGINT NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    custom_image_url VARCHAR(500),
    `condition` VARCHAR(50) NOT NULL,
    language VARCHAR(50) NOT NULL DEFAULT 'EN',
    price DECIMAL(12,2) NOT NULL,
    cost DECIMAL(12,2),
    on_hand INT NOT NULL DEFAULT 1,
    reserved INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'SHOW',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_products_seller (seller_id),
    KEY idx_products_card (card_id),
    KEY idx_products_status_price (status, price),
    CONSTRAINT chk_products_price CHECK (price > 0),
    CONSTRAINT chk_products_stock CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand),
    CONSTRAINT fk_products_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_products_card FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_product (user_id, product_id),
    CONSTRAINT chk_cart_quantity CHECK (quantity > 0),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL,
    order_number VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(12,2) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_order_number (order_number),
    UNIQUE KEY uq_orders_buyer_idempotency (buyer_id, idempotency_key),
    KEY idx_orders_buyer_created (buyer_id, created_at),
    CONSTRAINT chk_orders_total CHECK (total_amount >= 0),
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    product_name_snapshot VARCHAR(255) NOT NULL,
    price_at_purchase DECIMAL(12,2) NOT NULL,
    cost_at_purchase DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order (order_id),
    KEY idx_order_items_seller (seller_id),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS collection_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    `condition` VARCHAR(50),
    language VARCHAR(50) DEFAULT 'EN',
    quantity INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_collection_user_card (user_id, card_id),
    CONSTRAINT chk_collection_quantity CHECK (quantity > 0),
    CONSTRAINT fk_collection_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_collection_card FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    order_id BIGINT,
    seller_profile_id BIGINT,
    notification_type VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    read_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_notifications_recipient_created (recipient_id, created_at, id),
    KEY idx_notifications_recipient_read (recipient_id, read_at),
    CONSTRAINT chk_notifications_type CHECK (notification_type IN (
        'ORDER_RESERVED', 'ORDER_STATUS_CHANGED', 'SELLER_APPLICATION_SUBMITTED',
        'SELLER_APPLICATION_APPROVED', 'SELLER_APPLICATION_REJECTED'
    )),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_seller_profile FOREIGN KEY (seller_profile_id) REFERENCES seller_profiles(id) ON DELETE CASCADE
);
