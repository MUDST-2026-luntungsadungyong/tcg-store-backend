USE tcg_store_test;

-- Password for both accounts: password123
INSERT IGNORE INTO users
  (id, email, password, full_name, phone, contact_line, contact_facebook, role, seller_status)
VALUES
  (1, 'buyer@mythix.com', '$2b$12$yt.ju9w4yQHb8cnk90GnqeVDPbyiTEVKZI8VZ3qMe6KBYbTegzU0O', 'Ash Ketchum', '081-111-2222', 'ash_ketchum', 'facebook.com/ash.ketchum', 'USER', 'NONE'),
  (2, 'seller@mythix.com', '$2b$12$yt.ju9w4yQHb8cnk90GnqeVDPbyiTEVKZI8VZ3qMe6KBYbTegzU0O', 'Brock Pewter', '089-999-8888', 'brock_cards', 'facebook.com/brock.pewter', 'USER', 'APPROVED');

INSERT IGNORE INTO seller_profiles
  (id, user_id, shop_name, shop_description, contact_phone, contact_line, contact_facebook, verified_at)
VALUES
  (1, 2, 'Pewter City Card Vault', 'Test-cloud storefront for seller grouping and checkout flows.', '089-999-8888', 'brock_cards', 'facebook.com/brock.pewter', CURRENT_TIMESTAMP(6));

INSERT IGNORE INTO cards
  (id, name, supertype, subtypes, hp, types, rarity, set_name, card_number, artist, image_small, image_large, market_price)
VALUES
  ('pop4-4', 'Mew', 'Pokemon', 'Basic', '70', 'Psychic', 'Rare', 'POP Series 4', '4', 'Nakaoka', 'https://images.pokemontcg.io/pop4/4.png', 'https://images.pokemontcg.io/pop4/4_hires.png', 3500.00),
  ('neo3-3', 'Celebi', 'Pokemon', 'Basic', '50', 'Psychic', 'Rare Holo', 'Neo Revelation', '3', 'Hironobu Yoshida', 'https://images.pokemontcg.io/neo3/3.png', 'https://images.pokemontcg.io/neo3/3_hires.png', 220.00),
  ('base5-3', 'Dark Blastoise', 'Pokemon', 'Stage 2', '70', 'Water', 'Rare Holo', 'Team Rocket', '3', 'Mitsuhiro Arita', 'https://images.pokemontcg.io/base5/3.png', 'https://images.pokemontcg.io/base5/3_hires.png', 220.00);

INSERT IGNORE INTO products
  (id, seller_id, card_id, custom_image_url, `condition`, language, price, cost, on_hand, reserved, status)
VALUES
  (1, 2, 'pop4-4', NULL, 'Near Mint', 'EN', 1250.00, 800.00, 5, 0, 'SHOW'),
  (2, 2, 'neo3-3', NULL, 'Near Mint', 'EN', 450.00, 250.00, 8, 0, 'SHOW'),
  (3, 2, 'base5-3', NULL, 'Lightly Played', 'EN', 320.00, 180.00, 6, 0, 'SHOW');
