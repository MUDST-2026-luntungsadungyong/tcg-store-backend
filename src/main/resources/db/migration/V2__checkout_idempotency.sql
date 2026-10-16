-- Flyway V2: add durable checkout idempotency support.
-- Safe to run more than once. Execute with:
-- docker compose -f docker-compose.desktop.yml exec -T postgres-local psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
--   -f /migrations/001_checkout_idempotency.sql

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_orders_buyer_idempotency'
          AND conrelid = 'orders'::regclass
    ) THEN
        ALTER TABLE orders
            ADD CONSTRAINT uq_orders_buyer_idempotency
            UNIQUE (buyer_id, idempotency_key);
    END IF;
END $$;
