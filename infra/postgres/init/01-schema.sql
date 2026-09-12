-- =============================================================================
-- Mythix TCG Platform — Database Schema Initialization (DDL)
-- Target Database: PostgreSQL 16
-- =============================================================================

-- 1. Table: users
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone VARCHAR(50),
    contact_line VARCHAR(100),
    contact_facebook VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    seller_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. Table: seller_profiles
CREATE TABLE IF NOT EXISTS seller_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_name VARCHAR(255) NOT NULL,
    shop_description TEXT,
    contact_phone VARCHAR(50),
    contact_line VARCHAR(100),
    contact_facebook VARCHAR(255),
    verified_at TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3. Table: cards (Master Pokémon Catalog from API)
CREATE TABLE IF NOT EXISTS cards (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    supertype VARCHAR(50),
    subtypes VARCHAR(100),
    hp VARCHAR(10),
    types VARCHAR(50),
    rarity VARCHAR(100),
    set_name VARCHAR(255),
    card_number VARCHAR(50),
    artist VARCHAR(255),
    image_small VARCHAR(500),
    image_large VARCHAR(500),
    market_price DECIMAL(12, 2) DEFAULT 0.00
);

-- 4. Table: products (Seller listings & stock)
CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(100) NOT NULL REFERENCES cards(id),
    custom_image_url VARCHAR(500),
    condition VARCHAR(50) NOT NULL,
    language VARCHAR(50) NOT NULL DEFAULT 'EN',
    price DECIMAL(12, 2) NOT NULL,
    cost DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    on_hand INT NOT NULL DEFAULT 0,
    reserved INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'SHOW',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_stock_non_negative CHECK (on_hand >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_reserved_within_stock CHECK (reserved <= on_hand)
);

-- 5. Table: cart_items
CREATE TABLE IF NOT EXISTS cart_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity INT NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_product UNIQUE (user_id, product_id)
);

-- 6. Table: orders
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    idempotency_key VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(12, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_orders_buyer_idempotency UNIQUE (buyer_id, idempotency_key)
);

-- 7. Table: order_items (Snapshot pattern)
CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id),
    seller_id BIGINT NOT NULL REFERENCES users(id),
    card_id VARCHAR(100) NOT NULL,
    product_name_snapshot VARCHAR(255) NOT NULL,
    price_at_purchase DECIMAL(12, 2) NOT NULL,
    cost_at_purchase DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    quantity INT NOT NULL CHECK (quantity > 0)
);

-- 8. Table: collection_items
CREATE TABLE IF NOT EXISTS collection_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(100) NOT NULL REFERENCES cards(id),
    condition VARCHAR(50),
    language VARCHAR(50) DEFAULT 'EN',
      quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
      created_at TIMESTAMP NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
      CONSTRAINT uq_collection_user_card UNIQUE (user_id, card_id)
    );

-- Performance Indexes
CREATE INDEX IF NOT EXISTS idx_cards_name ON cards(name);
CREATE INDEX IF NOT EXISTS idx_cards_rarity ON cards(rarity);
CREATE INDEX IF NOT EXISTS idx_cards_supertype ON cards(supertype);
CREATE INDEX IF NOT EXISTS idx_products_seller_id ON products(seller_id);
CREATE INDEX IF NOT EXISTS idx_products_card_id ON products(card_id);
CREATE INDEX IF NOT EXISTS idx_products_status ON products(status);
CREATE INDEX IF NOT EXISTS idx_orders_buyer_id ON orders(buyer_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_seller_id ON order_items(seller_id);
CREATE INDEX IF NOT EXISTS idx_collection_user_id ON collection_items(user_id);
