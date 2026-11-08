-- US-27-1. Append-only facts permit catch-up without valuing old weeks at today's prices.
CREATE TABLE admin_report_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    tracking_since TIMESTAMP NOT NULL
);
INSERT INTO admin_report_state VALUES (1, timezone('UTC', statement_timestamp()));

CREATE TABLE admin_inventory_events (
    id BIGSERIAL PRIMARY KEY,
    local_card_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    card_name VARCHAR(255) NOT NULL,
    quantity_delta INTEGER NOT NULL,
    on_hand INTEGER NOT NULL,
    price NUMERIC(12,2) NOT NULL,
    cost NUMERIC(12,2) NOT NULL,
    listing_created_at TIMESTAMP NOT NULL,
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_admin_inventory_history ON admin_inventory_events(local_card_id, occurred_at DESC, id DESC);
CREATE INDEX idx_admin_inventory_period ON admin_inventory_events(occurred_at);

-- Baselines are NOT counted as new stock in the first week.
INSERT INTO admin_inventory_events(local_card_id, seller_id, card_id, card_name,
    quantity_delta, on_hand, price, cost, listing_created_at, occurred_at)
SELECT l.id, l.seller_id, l.global_card_id, c.name, 0, l.on_hand, l.price, l.cost,
       l.created_at, s.tracking_since
FROM local_cards l JOIN global_cards c ON c.id = l.global_card_id CROSS JOIN admin_report_state s;

CREATE TABLE admin_order_events (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    phase VARCHAR(20) NOT NULL,
    platform_fee NUMERIC(12,2) NOT NULL,
    items_json TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_admin_order_event UNIQUE(order_id, phase)
);
CREATE INDEX idx_admin_order_event_period ON admin_order_events(occurred_at);

-- JSON is kept as TEXT for the same API format across database engines.
CREATE TABLE admin_weekly_reports (
    id BIGSERIAL PRIMARY KEY,
    week_start_date DATE NOT NULL,
    week_end_date DATE NOT NULL UNIQUE,
    period_start TIMESTAMP NOT NULL,
    period_end TIMESTAMP NOT NULL,
    total_inventory_value NUMERIC(20,2) NOT NULL,
    total_fees_collected NUMERIC(20,2) NOT NULL,
    top_performing_cards TEXT NOT NULL,
    stagnant_inventory_summary TEXT NOT NULL,
    payload TEXT NOT NULL,
    generated_at TIMESTAMP NOT NULL
);

CREATE FUNCTION capture_admin_inventory_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p local_cards; delta INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN p := OLD; delta := -OLD.on_hand;
    ELSIF TG_OP = 'INSERT' THEN p := NEW; delta := NEW.on_hand;
    ELSE
        IF NEW.on_hand = OLD.on_hand AND NEW.price = OLD.price AND NEW.cost = OLD.cost
            AND NEW.global_card_id = OLD.global_card_id THEN RETURN NEW; END IF;
        p := NEW; delta := NEW.on_hand - OLD.on_hand;
    END IF;
    INSERT INTO admin_inventory_events(local_card_id, seller_id, card_id, card_name,
        quantity_delta, on_hand, price, cost, listing_created_at, occurred_at)
    SELECT p.id, p.seller_id, p.global_card_id, c.name, delta,
        CASE WHEN TG_OP = 'DELETE' THEN 0 ELSE p.on_hand END,
        p.price, p.cost, p.created_at, timezone('UTC', clock_timestamp())
    FROM global_cards c WHERE c.id = p.global_card_id;
    RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER trg_admin_inventory_event AFTER INSERT OR UPDATE OR DELETE ON local_cards
    FOR EACH ROW EXECUTE FUNCTION capture_admin_inventory_event();

CREATE FUNCTION capture_admin_order_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.to_status IN ('SENT', 'FULFILLED') THEN
        INSERT INTO admin_order_events(order_id, phase, platform_fee, items_json, occurred_at)
        SELECT o.id, NEW.to_status, o.platform_fee,
            COALESCE((SELECT jsonb_agg(jsonb_build_object(
                'productId', i.local_card_id, 'cardId', i.global_card_id,
                'name', i.card_name_snapshot, 'quantity', i.quantity,
                'unitPrice', i.price_at_purchase, 'unitCost', i.cost_at_purchase
            ) ORDER BY i.id)::text FROM order_items i WHERE i.order_id = o.id), '[]'),
            timezone('UTC', clock_timestamp())
        FROM orders o WHERE o.id = NEW.order_id
        ON CONFLICT (order_id, phase) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_admin_order_event AFTER INSERT ON order_status_logs
    FOR EACH ROW EXECUTE FUNCTION capture_admin_order_event();

-- Preserve recent pre-installation activity for the 60-day inactivity check only.
-- Legacy TIMESTAMP columns are interpreted as UTC, as with the existing API.
INSERT INTO admin_order_events(order_id, phase, platform_fee, items_json, occurred_at)
SELECT o.id, h.to_status, o.platform_fee,
    COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'productId', i.local_card_id, 'cardId', i.global_card_id,
        'name', i.card_name_snapshot, 'quantity', i.quantity,
        'unitPrice', i.price_at_purchase, 'unitCost', i.cost_at_purchase
    ) ORDER BY i.id)::text FROM order_items i WHERE i.order_id = o.id), '[]'),
    MAX(h.created_at)
FROM orders o JOIN order_status_logs h ON h.order_id = o.id
WHERE h.to_status IN ('SENT', 'FULFILLED')
GROUP BY o.id, h.to_status;
