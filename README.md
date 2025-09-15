# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) providing a core library and REST API for running a mint.

The mint now exposes a shared `SignatureVaultService` bean so that signatures minted in one request can be restored in a later request. When calling protocol methods such as `NUT04.mint` or `NUT09.restore` directly, pass the `SignatureVaultService` instance to ensure signatures persist across calls.

## Modules

- `cashu-mint-protocol` – core library for the Cashu protocol.
- `cashu-mint-rest` – REST API for running a mint.
- `cashu-mint-admin` – administrative module (see `cashu-mint-admin/project/specification.md`).

## Admin CLI

The `cashu-mint-admin` module now exposes a Picocli-based command line entry point for
day-to-day mint operations. Run the CLI with the Maven wrapper or a packaged jar:

```bash
./mvnw -pl cashu-mint-admin -q exec:java \
  -Dexec.mainClass=xyz.tcheeric.cashu.mint.admin.cli.MintAdminCliApplication -- mint --output-format=JSON
```

Available commands:

- `mint` – shows a summary of the mint's lifecycle state and alert counts.
- `mint config` – inspects or applies configuration payloads (`--payload` inline or
  `--payload-file` pointing to JSON/YAML content).
- `mint users` – lists operator accounts, optionally including inactive users via
  `--include-inactive` or a structured payload.
- `mint alerts` – displays alert information with a configurable severity filter.

Commands accept JSON payloads by default (`--input-format=JSON`) and can switch to
YAML with `--input-format=YAML`. Responses default to tabular output (`--output-format=TABLE`)
but can emit prettified JSON.

## Admin persistence

The administrative module now ships with JDBC-based repositories for mint aggregates, configuration history, and an
event-dispatch outbox. The relational schema is versioned with Flyway migrations stored in
`cashu-mint-admin/src/main/resources/db/migration`, and integration tests exercise the repositories against an in-memory H2
database to verify persistence and rehydration behaviour.

## Test data preload

Generate deterministic preload data in two steps: emit JSON using the `MintPreloadDataGenerator`, then render SQL from that JSON via `MintPreloadSqlRenderer` (exposed through `scripts/render-preload-sql.sh`).

```bash
# Step 1: create JSON preload data (optionally pass a mint UUID as the second argument)
./mvnw -q -pl cashu-mint-protocol exec:java \
  -Dexec.mainClass=xyz.tcheeric.cashu.mint.tools.MintPreloadDataGenerator \
  -Dexec.args="scripts/preload-test-data.json"
# ./mvnw -q -pl cashu-mint-protocol exec:java \
#   -Dexec.mainClass=xyz.tcheeric.cashu.mint.tools.MintPreloadDataGenerator \
#   -Dexec.args="scripts/preload-test-data.json 11111111-1111-1111-1111-111111111111"

# Step 2: transform the JSON into the SQL preload script
./scripts/render-preload-sql.sh scripts/preload-test-data.json scripts/preload-test-data.sql

# Step 3: load the generated preload into Postgres
psql -d cashu_mint -f scripts/preload-test-data.sql
```

The generator keeps the mint, keyset, and key material in memory so tests can reuse the values before the JSON is written to disk. It deterministically derives the database key identifiers from the mint id (supply your own UUID for reproducible output) and computes the keyset identifier from the generated key material. The renderer consumes the generated JSON and injects the values into the SQL template used to seed the database during environment creation.

## Documentation

Documentation following the [Diátaxis](https://diataxis.fr/) framework is available in [docs](docs/README.md).

- Gateway configuration (method/unit mappings): see docs/how-to/configure-gateways.md.
