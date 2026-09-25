-- Revision 5 compatibility migration for legacy local PostgreSQL volumes.
-- Additive and repeatable: preserves existing users and seller profiles.

ALTER TABLE seller_profiles
    ADD COLUMN IF NOT EXISTS id_card_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS id_card_image_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS bank_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bank_account_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bank_account_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS rejected_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

-- Map the legacy application state to the Revision 5 state model.
UPDATE users SET seller_status = 'APPROVED' WHERE seller_status = 'VERIFIED';
UPDATE users SET role = 'USER' WHERE role = 'SELLER';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_role_revision5') THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_role_revision5 CHECK (role IN ('USER', 'ADMIN'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_seller_status_revision5') THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_seller_status_revision5 CHECK (seller_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_seller_profiles_pending ON seller_profiles (requested_at ASC)
    WHERE requested_at IS NOT NULL;
