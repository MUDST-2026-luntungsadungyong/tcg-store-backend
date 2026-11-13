-- Mark reports reconstructed from old order history and distinguish unavailable stock history.
ALTER TABLE admin_weekly_reports
    ADD COLUMN report_type VARCHAR(32) NOT NULL DEFAULT 'TRACKED';

ALTER TABLE admin_weekly_reports
    ADD CONSTRAINT chk_admin_weekly_report_type
        CHECK (report_type IN ('TRACKED', 'RECONSTRUCTED_SALES_ONLY'));

ALTER TABLE admin_weekly_reports
    ALTER COLUMN total_inventory_value DROP NOT NULL;

UPDATE admin_weekly_reports
SET payload = (payload::jsonb || jsonb_build_object('reportType', report_type))::text;
