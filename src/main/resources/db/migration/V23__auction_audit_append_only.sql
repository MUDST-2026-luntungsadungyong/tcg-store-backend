-- Auction security events are append-only to the application database role.
-- A privileged database owner can disable triggers; operational access controls remain required.
CREATE OR REPLACE FUNCTION protect_auction_audit_log() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'TRUNCATE' OR OLD.target_type LIKE 'AUCTION%' THEN
    RAISE EXCEPTION 'Auction audit records are append-only';
  END IF;
  IF TG_OP = 'UPDATE' THEN RETURN NEW; END IF;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER protect_auction_audit_row BEFORE UPDATE OR DELETE ON audit_logs
  FOR EACH ROW EXECUTE FUNCTION protect_auction_audit_log();
CREATE TRIGGER protect_auction_audit_truncate BEFORE TRUNCATE ON audit_logs
  FOR EACH STATEMENT EXECUTE FUNCTION protect_auction_audit_log();

-- Preserve the actor identifier even when an admin account is deleted. The audit
-- history deliberately outlives its user row, so it cannot retain a foreign key.
DO $$
DECLARE fk record;
BEGIN
  FOR fk IN SELECT conname FROM pg_constraint
    WHERE conrelid = 'audit_logs'::regclass AND confrelid = 'users'::regclass AND contype = 'f'
  LOOP
    EXECUTE format('ALTER TABLE audit_logs DROP CONSTRAINT %I', fk.conname);
  END LOOP;
END $$;
