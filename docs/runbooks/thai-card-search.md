# Thai card search rollout

`/api/cards` and `/api/products` search catalog names and aliases. Listings continue to use their existing card IDs, visibility, language and price filters. A standalone Pokémon game-name query opens the normal browse results. The alias describes a searchable name, so an English printing can match a Thai query without changing the printing language.

## Data

`infra/postgres/catalog/card-search-names-th.csv` covers the 2,000 cards in the repository seed. Each row records its source. It includes official Pokédex species names, names with a card suffix derived from the English catalog, common search spellings, and descriptive Thai aliases for older Trainer and Energy cards. `card-search-names-neon-th.csv` covers the two additional active catalog IDs found on the configured Neon database on 2026-11-11. Compare with the target catalog before rollout and add reviewed names for any extra active cards. If a target contains a subset, prepare a CSV for that subset. Keep stable catalog IDs and review ambiguous matches before importing.

## Release order

1. Rehearse the packaged Flyway upgrade through V19 on a disposable copy of the target schema. The migration creates `card_search_names` and its card-first unique index.
2. Apply the approved migration chain through V19 using the existing database migration entry point. Where the target is at V17, include V18 in the release rehearsal and review its auction repair before applying the chain.
3. Run the import against that database. Use an operator Python environment with `psycopg[binary]` installed and the target's `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, TLS settings and credential provider configured. Run from the backend directory.

   ```sh
   python scripts/import-card-search-names.py infra/postgres/catalog/card-search-names-th.csv
   python scripts/import-card-search-names.py infra/postgres/catalog/card-search-names-th.csv --apply
   ```

   The first command audits the proposed import. For the configured Neon catalog, pass `infra/postgres/catalog/card-search-names-neon-th.csv` as a second CSV argument to both commands. Both commands fail for unknown IDs, missing Thai coverage, or an empty active catalog. Applying the same files again inserts zero rows. Add reviewed names to the import whenever adding new catalog cards, then rerun the audit.
4. Deploy the verified backend image through staging and the production promotion workflow. The existing frontend sends the search string to these endpoints. Check the catalog and storefront for a Thai species name, a Trainer or Energy alias, an English name, and a Thai Pokémon game-name query. In the storefront also check that a hidden listing stays hidden.

The schema and alias import are additive. If an application rollback is needed, redeploy the previous backend image and keep the catalog search names for the next rollout.
