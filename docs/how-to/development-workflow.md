# Development workflow

This how-to guide walks through the daily development loop: preparing infrastructure, applying database migrations, running tests, and launching the REST service.

## Prerequisites

- Java 21 with `JAVA_HOME` configured so Maven can compile the modules (see [`pom.xml`](../../pom.xml)).
- Docker and Docker Compose for starting the Postgres databases and companion services defined in [`docker-compose.yml`](../../docker-compose.yml).

## Start supporting services

1. Start the databases used by the mint, vault, and gateway components:
   ```bash
   docker compose --profile dev up -d cashu-mint-db cashu-vault-db cashu-gateway-db
   ```
   These containers expose healthy Postgres instances on ports 55432–55434 for local development (see [`docker-compose.yml`](../../docker-compose.yml)).
2. Bring up the dependent services that manage schema and gateway integrations:
   ```bash
   docker compose --profile dev up -d cashu-vault-jpa cashu-gateway-rest cashu-gateway-webhook
   ```
   The vault service applies its schema as it starts and exposes the API consumed by the mint; the gateway services wire REST calls to BOLT11 handlers with auto-DDL enabled for local use (see [`docker-compose.yml`](../../docker-compose.yml)).

## Run database migrations

1. Ensure the `cashu-vault-jpa` container is running (see above). It initialises its database automatically before advertising readiness, so no extra Flyway/Liquibase command is required for the vault schema (see [`docker-compose.yml`](../../docker-compose.yml)).
2. Optionally seed the mint database with deterministic test data:
   ```bash
   ./mvnw -q -pl cashu-mint-protocol compile exec:java@mint-preload-json
   ./mvnw -q -pl cashu-mint-protocol initialize exec:java@mint-preload-sql
   psql -d cashu_mint -f scripts/preload-test-data.sql
   ```
   The generator and renderer now ship as Maven executions in the protocol module so you can override arguments via
   [`mint-preload.properties`](../../cashu-mint-protocol/mint-preload.properties) or command-line `-D` overrides (see
   [`README.md`](../../README.md) for details).

## Run tests

Execute the full build from the repository root before opening a pull request:

```bash
mvn -q verify
```

The dedicated test guide contains additional context if you need to customise the command (see [`run-tests.md`](./run-tests.md)).

## Launch the REST API

Start the Spring Boot application when you want to exercise endpoints locally:

```bash
./mvnw -pl cashu-mint-rest spring-boot:run
```

The REST module packages a standard Spring Boot entry point and Maven plugin configuration, so this command compiles the protocol module, starts `CashuMintRestApplication`, and wires the controllers discussed in the module reference (see [`cashu-mint-rest/pom.xml`](../../cashu-mint-rest/pom.xml) and [`CashuMintRestApplication.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java)).

## Iterate with clients or admin tools

Use the `CashuClient` helpers or the administrative domain model to script manual tests against the running REST service without duplicating HTTP plumbing or lifecycle rules (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java) and [`MintAggregate.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/domain/MintAggregate.java)).
