# Tools Reference

This page documents the developer tooling packaged in the `cashu-mint-tools` module for generating deterministic preload data and rendering SQL fixtures.

## Profiles

- `preload-json` – runs the JSON generator (`MintPreloadDataGenerator`).
- `preload-sql` – runs the SQL renderer (`MintPreloadSqlRenderer`).
- `preload-all` – runs both in sequence (JSON first, then SQL) bound to the `validate` phase.

## Commands

- One‑shot (emit JSON, then render SQL):
  - `./mvnw -q -pl cashu-mint-tools -Ppreload-all validate`
  - Load into Postgres (docker-compose dev defaults):
    - Host connection:
      - `PGPASSWORD=postgres psql -h localhost -p 55432 -U postgres -d cashu_mint -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql`
      - or `psql "postgresql://postgres:postgres@localhost:55432/cashu_mint" -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql`
    - Inside container:
      - `docker compose exec -T cashu-mint-db psql -U postgres -d cashu_mint -v ON_ERROR_STOP=1 < scripts/preload-test-data.sql`

- Individual steps:
  - JSON: `./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java`
  - SQL: `./mvnw -q -pl cashu-mint-tools -Ppreload-sql exec:java`

## Defaults and overrides

- Defaults come from `cashu-mint-tools/mint-preload.properties`.
- Override with `-D` properties, for example:
  - `-Dmint.preload.mint-id=$(uuidgen)`
  - `-Dmint.preload.json.output=target/preload.json`
  - `-Dmint.preload.sql.input=target/preload.json`
  - `-Dmint.preload.sql.output=target/preload.sql`
