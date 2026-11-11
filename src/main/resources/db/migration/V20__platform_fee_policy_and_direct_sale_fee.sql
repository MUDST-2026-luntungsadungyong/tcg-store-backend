CREATE TABLE IF NOT EXISTS platform_fee_policy (
    id INTEGER PRIMARY KEY,
    rate NUMERIC(5, 4) NOT NULL,
    minimum_fee NUMERIC(12, 2) NOT NULL,
    CONSTRAINT chk_platform_fee_policy_id CHECK (id = 1),
    CONSTRAINT chk_platform_fee_policy_rate CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT chk_platform_fee_policy_minimum CHECK (minimum_fee >= 0)
);

INSERT INTO platform_fee_policy (id, rate, minimum_fee)
VALUES (1, 0.0500, 20.00)
ON CONFLICT (id) DO NOTHING;
