-- Flyway V10: align persisted order states with the current reservation flow.
-- PAID is the legacy equivalent of CONFIRMED. Convert it before tightening the
-- constraint so existing orders remain valid during an upgrade.

UPDATE orders
SET status = 'CONFIRMED'
WHERE status = 'PAID';

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING', 'CONFIRMED', 'SENT', 'FULFILLED', 'CANCELLED'));
