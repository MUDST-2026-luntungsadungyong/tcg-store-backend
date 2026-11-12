-- Flyway V12: Shopee-style Discount and Voucher System
-- =============================================================================
-- Master vouchers, user voucher wallet, and order discount auditing
-- =============================================================================

-- 1. Table: vouchers
CREATE TABLE IF NOT EXISTS vouchers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    discount_value DECIMAL(12, 2) NOT NULL CHECK (discount_value > 0),
    min_spend DECIMAL(12, 2) NOT NULL DEFAULT 0.00 CHECK (min_spend >= 0),
    max_discount_amount DECIMAL(12, 2) CHECK (max_discount_amount IS NULL OR max_discount_amount > 0),
    total_quota INT NOT NULL DEFAULT 100 CHECK (total_quota > 0),
    used_quota INT NOT NULL DEFAULT 0 CHECK (used_quota >= 0 AND used_quota <= total_quota),
    per_user_limit INT NOT NULL DEFAULT 1 CHECK (per_user_limit > 0),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_vouchers_code ON vouchers(code);
CREATE INDEX IF NOT EXISTS idx_vouchers_active_dates ON vouchers(is_active, start_time, end_time);

-- 2. Table: user_vouchers
CREATE TABLE IF NOT EXISTS user_vouchers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    voucher_id BIGINT NOT NULL REFERENCES vouchers(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'CLAIMED' CHECK (status IN ('CLAIMED', 'USED', 'EXPIRED')),
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP,
    order_id BIGINT REFERENCES orders(id) ON DELETE SET NULL,
    CONSTRAINT uq_user_voucher UNIQUE (user_id, voucher_id)
);

CREATE INDEX IF NOT EXISTS idx_user_vouchers_user_status ON user_vouchers(user_id, status);

-- 3. Modify orders to track subtotal and discounts
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS subtotal_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS voucher_id BIGINT REFERENCES vouchers(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS voucher_code_snapshot VARCHAR(50);

-- Backfill subtotal_amount for existing orders
UPDATE orders SET subtotal_amount = total_amount WHERE subtotal_amount = 0.00 AND total_amount > 0.00;

-- 4. Initial Seed Vouchers
INSERT INTO vouchers (code, title, description, discount_type, discount_value, min_spend, max_discount_amount, total_quota, used_quota, per_user_limit, start_time, end_time, is_active)
VALUES
    ('WELCOME10', 'ส่วนลด 10% สมาชิกใหม่', 'ลด 10% สูงสุด 100 บาท เมื่อซื้อขั้นต่ำ 300 บาท', 'PERCENTAGE', 10.00, 300.00, 100.00, 500, 0, 1, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '90 days', TRUE),
    ('MYTHIX50', 'ส่วนลดพิเศษ ฿50', 'ลดทันที 50 บาท เมื่อช้อปการ์ดครบ 500 บาท', 'FIXED_AMOUNT', 50.00, 500.00, NULL, 300, 0, 1, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '60 days', TRUE),
    ('FLASH100', 'Flash Sale ฿100', 'ลด 100 บาท สำหรับยอดสั่งซื้อตั้งแต่ 1,000 บาทขึ้นไป', 'FIXED_AMOUNT', 100.00, 1000.00, NULL, 100, 0, 1, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '30 days', TRUE),
    ('POKEMON15', 'Mega Deal Pokemon 15%', 'ลด 15% สูงสุด 200 บาท เมื่อซื้อขั้นต่ำ 800 บาท', 'PERCENTAGE', 15.00, 800.00, 200.00, 200, 0, 1, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '45 days', TRUE)
ON CONFLICT (code) DO NOTHING;
