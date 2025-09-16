# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) providing a core library and REST API for running a mint.

The mint now exposes a shared `SignatureVaultService` bean so that signatures minted in one request can be restored in a later request. When calling protocol methods such as `NUT04.mint` or `NUT09.restore` directly, pass the `SignatureVaultService` instance to ensure signatures persist across calls.

## Modules

- `cashu-mint-protocol` – core library for the Cashu protocol.
- `cashu-mint-rest` – REST API for running a mint.
- `cashu-mint-admin` – administrative module (see `cashu-mint-admin/project/specification.md`).

## Admin module bootstrap

The `cashu-mint-admin` module now boots as a Spring Boot CLI application backed by Picocli. It includes hardened JDBC
configuration for PostgreSQL and H2 development profiles, plus Flyway and Liquibase hooks for future migrations. Observability
is enabled out of the box with actuator endpoints, tracing identifiers in log patterns, and structured error handling defaults
in `application.yml`.

Start the admin CLI with the lightweight in-memory profile while building new workflows:

```bash
./mvnw -q -pl cashu-mint-admin spring-boot:run -Dspring-boot.run.profiles=h2
```

Switch to PostgreSQL (or override the connection string via environment variables) for integration testing:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/cashu_mint_admin \
SPRING_DATASOURCE_USERNAME=cashu_admin \
SPRING_DATASOURCE_PASSWORD=change_me \
./mvnw -q -pl cashu-mint-admin spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway migrations are resolved from `classpath:db/migration/admin/**` while Liquibase change logs default to
`classpath:db/changelog/db.changelog-master.yaml` when enabled. Adjust tracing, logging, or migration toggles directly in
`cashu-mint-admin/src/main/resources/application.yml`.

The REST module now exposes authenticated administrative endpoints under `/admin` for
mint lifecycle, configuration, operator management, and alert workflows. Each route now
returns structured responses that mirror the CLI experience, enforces token-based
authentication (`X-Admin-Token`), and checks role membership via `X-Admin-Roles`.
OpenAPI documentation is published at runtime so operators can explore the admin
surface from `/v3/api-docs` or the bundled Swagger UI.

## Admin CLI

The `cashu-mint-admin` module now exposes a Picocli-based command line entry point for
day-to-day mint operations. Run the CLI with the Maven wrapper or a packaged jar:

```bash
./mvnw -pl cashu-mint-admin -q exec:java \
  -Dexec.mainClass=xyz.tcheeric.cashu.mint.admin.cli.MintAdminCliApplication -- mint --output-format=JSON
```

Available commands:

- `mint` – shows a summary of the mint's lifecycle state and alert counts.
- `mint create` – provisions a new mint after a confirmation prompt (skip with `--yes`).
- `mint update` – applies configuration version bumps for an existing mint.
- `mint pause` / `mint resume` / `mint retire` – drive lifecycle transitions with
  idempotency checks (repeating a command returns a structured no-op response).
- `mint config` – inspects or applies configuration payloads (`--payload` inline or
  `--payload-file` pointing to JSON/YAML content).
- `mint users` – lists operator accounts, optionally including inactive users via
  `--include-inactive` or a structured payload.
- `mint alerts` – displays alert information with a configurable severity filter.

Commands accept JSON payloads by default (`--input-format=JSON`) and can switch to
YAML with `--input-format=YAML`. Lifecycle commands also accept option-derived input
(`--mint-id`, `--operator-id`, `--version-tag`) and emit machine-readable output that
signals whether a change occurred. Responses default to tabular output
(`--output-format=TABLE`) but can emit prettified JSON for scripting. Lifecycle
summaries are produced by shared presenters so the CLI tables, JSON payloads, and
REST `LifecycleActionResponse` structures remain identical.

Each CLI invocation carries a traceable identifier. Provide `--request-id` to reuse a
known UUID (the CLI generates one automatically when omitted) and `--correlation-id`
to link the action to external change-management tickets. Both identifiers flow
through the lifecycle interactor and into audit metadata so outbox events and
observability tooling can align terminal activity with persisted history.

## Admin persistence

The administrative module now ships with JDBC-based repositories for mint aggregates, configuration history, lifecycle history
projections, and an event-dispatch outbox. The relational schema is versioned with Flyway migrations stored in
`cashu-mint-admin/src/main/resources/db/migration`, and integration tests exercise the repositories against an in-memory H2
database to verify persistence and rehydration behaviour. Lifecycle audit events now capture the active configuration revision
and a snapshot of the notification policy so downstream tooling can trace each state change back to the exact policy and
configuration in effect when it occurred. Domain lifecycle events are projected into dedicated snapshot and history tables
while simultaneously being serialised into the transactional outbox for external dispatch. A dedicated
`LifecycleEventOutboxHandler` now reconstructs these events post-commit so the read models stay synchronised via the
`LifecycleEventOutboxDispatcher`, which can be scheduled through the `LifecycleEventOutboxTask` runnable in queue-driven or
polling deployments.

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
