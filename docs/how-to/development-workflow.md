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
   # one-shot: emit JSON then render SQL
   ./mvnw -q -pl cashu-mint-tools -Ppreload-all validate

   # seed the mint DB using docker-compose credentials/port (host connection)
   PGPASSWORD=postgres psql \
     -h localhost -p 55432 -U postgres -d cashu_mint \
     -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql

   # or URI style
   psql "postgresql://postgres:postgres@localhost:55432/cashu_mint" \
     -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql

   # or stream into the DB container
   docker compose exec -T cashu-mint-db psql -U postgres -d cashu_mint -v ON_ERROR_STOP=1 < scripts/preload-test-data.sql

   # run steps individually (same seeding commands as above)
   ./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java
   ./mvnw -q -pl cashu-mint-tools -Ppreload-sql exec:java
   ```
   The profiles load defaults from [`cashu-mint-tools/mint-preload.properties`](../../cashu-mint-tools/mint-preload.properties) so the JSON and SQL destinations stay aligned. Override any property with `-D` flags when you need alternative locations or a specific mint identifier (see [`README.md`](../../README.md)).

   If the schema does not exist yet, the preload script will safely do nothing. To create tables for local development, you can start the mint REST service with Hibernate auto-DDL enabled via the included override file, then re-run the `psql` command:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile dev up -d cashu-mint-rest
   # after the service initializes the schema, seed again
   PGPASSWORD=postgres psql -h localhost -p 55432 -U postgres -d cashu_mint -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql
   ```

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
