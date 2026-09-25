# Marketplace simulation

`MarketplaceSimulationIntegrationTests` creates 50 approved sellers, 50 buyers, 12 canonical cards, and 50 seller listings in the test database. It then runs deterministic random collection, cart, and checkout behavior through the real Spring services.

The test uses seed `20260925`, so a failure can be reproduced. It uses the `test` profile and cleans all generated rows after the test. It does not write to production or TiDB.

Run it with:

```text
./mvnw --batch-mode -Dtest=MarketplaceSimulationIntegrationTests test
```

The simulator currently verifies:

- exactly 50 approved sellers and 50 buyers are created;
- every seller has a listing owned by that seller;
- buyers can add canonical cards to collections;
- buyers can add listings to carts and complete checkout;
- order items keep seller, listing, card, and snapshot data;
- stock reservations remain non-negative.

For a live TiDB run, use a disposable test database and a separate profile/configuration. Do not point this destructive cleanup test at a shared or production database.
