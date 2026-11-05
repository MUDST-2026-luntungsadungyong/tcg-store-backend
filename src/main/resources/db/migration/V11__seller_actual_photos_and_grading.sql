-- Codex design decision: actual photographs and seller-declared grading belong
-- to the physical seller listing, never to the shared master catalog.
-- Nullable additions preserve existing listings without claiming legacy cover
-- images are actual front/back photographs or inventing grading information.
ALTER TABLE local_cards ADD COLUMN actual_image_front_url VARCHAR(500);
ALTER TABLE local_cards ADD COLUMN actual_image_back_url VARCHAR(500);
ALTER TABLE local_cards ADD COLUMN grading_company VARCHAR(10);
ALTER TABLE local_cards ADD COLUMN grade VARCHAR(30);
ALTER TABLE local_cards ADD COLUMN cert_number VARCHAR(100);

ALTER TABLE local_cards ADD CONSTRAINT local_cards_grading_company_check
    CHECK (grading_company IS NULL OR grading_company IN ('PSA', 'BGS', 'CGC'));
ALTER TABLE local_cards ADD CONSTRAINT local_cards_grading_details_check
    CHECK ((grading_company IS NULL AND grade IS NULL AND cert_number IS NULL)
        OR (grading_company IS NOT NULL AND grade IS NOT NULL AND cert_number IS NOT NULL));

CREATE TABLE product_actual_images (
    local_card_id BIGINT NOT NULL REFERENCES local_cards(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 3),
    image_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (local_card_id, position)
);
