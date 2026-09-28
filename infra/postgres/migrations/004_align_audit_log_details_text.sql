-- Align audit_logs.details with the JPA entity and the canonical bootstrap schema.
-- The cast preserves existing JSON values as their textual representation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'audit_logs'
          AND column_name = 'details'
          AND udt_name = 'jsonb'
    ) THEN
        ALTER TABLE audit_logs
            ALTER COLUMN details TYPE TEXT
            USING details::TEXT;
    END IF;
END
$$;
