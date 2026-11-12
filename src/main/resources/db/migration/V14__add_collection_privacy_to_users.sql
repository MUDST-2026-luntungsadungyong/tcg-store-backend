-- Flyway V14: Add collection privacy setting to users table
-- =============================================================================
-- Allows users (buyers and sellers) to toggle their collection album
-- between 'PUBLIC' and 'PRIVATE'. Default is 'PUBLIC'.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS collection_privacy VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';

-- Ensure values are restricted to PUBLIC or PRIVATE
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_collection_privacy'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_collection_privacy
            CHECK (collection_privacy IN ('PUBLIC', 'PRIVATE'));
    END IF;
END $$;
