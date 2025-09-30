# Development workflow

This how-to guide walks through the daily development loop: preparing infrastructure, applying database migrations, running tests, and launching the REST service.

## Prerequisites

- Java 21 with `JAVA_HOME` configured so Maven can compile the modules (see [`pom.xml`](../../pom.xml)).
- Docker and Docker Compose for starting the Postgres databases and companion services defined in [`docker-compose.yml`](../../docker-compose_bck.yml).

## Start supporting services

1. Start the databases used by the vault and gateway components:
   ```bash
   docker compose --profile dev up -d cashu-vault-db cashu-gateway-db
   ```
   These containers expose healthy Postgres instances on ports 55433–55434 for local development (see [`docker-compose.yml`](../../docker-compose_bck.yml)).
2. Bring up the dependent services that manage schema and gateway integrations:
   ```bash
   docker compose --profile dev up -d cashu-vault-jpa cashu-gateway-rest cashu-gateway-webhook
   ```
   The vault service applies its schema as it starts and exposes the API consumed by the mint; the gateway services wire REST calls to BOLT11 handlers with auto-DDL enabled for local use (see [`docker-compose.yml`](../../docker-compose_bck.yml)).

## Run database migrations

1. Ensure the `cashu-vault-jpa` container is running (see above). It initialises its database automatically before advertising readiness, so no extra Flyway/Liquibase command is required for the vault schema (see [`docker-compose.yml`](../../docker-compose_bck.yml)).
2. Seed the vault database with deterministic test data:
   ```bash
   # one-shot: emit JSON then render SQL
   ./mvnw -q -pl cashu-mint-tools -Ppreload-all validate

   # seed the vault DB using docker-compose credentials/port (host connection)
   PGPASSWORD=postgres psql \
     -h localhost -p 55433 -U postgres -d cashu_vault \
     -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql

   # or URI style
   psql "postgresql://postgres:postgres@localhost:55433/cashu_vault" \
     -v ON_ERROR_STOP=1 -f scripts/preload-test-data.sql

   # or stream into the DB container
   docker compose exec -T cashu-vault-db psql -U postgres -d cashu_vault -v ON_ERROR_STOP=1 < scripts/preload-test-data.sql

   # run steps individually (same seeding commands as above)
   ./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java
   ./mvnw -q -pl cashu-mint-tools -Ppreload-sql exec:java
   ```
   The profiles load defaults from [`cashu-mint-tools/mint-preload.properties`](../../cashu-mint-tools/mint-preload.properties) so the JSON and SQL destinations stay aligned. Override any property with `-D` flags when you need alternative locations or a specific mint identifier (see [`README.md`](../../README.md)). Ensure the vault service is up so its schema exists before seeding: `docker compose --profile dev up -d cashu-vault-db cashu-vault-jpa`.

## Run tests

Execute the full build from the repository root before opening a pull request:

```bash
mvn -q verify
```

The dedicated test guide contains additional context if you need to customise the command (see [`run-tests.md`](./run-tests.md)).

## Launch the REST API

Start the Spring Boot application when you want to exercise endpoints locally:

```bash
SPRING_PROFILES_ACTIVE=dev MINT_PRELOAD_JSON_INPUT=file:$(pwd)/scripts/preload-test-data.json \
  ./mvnw -pl cashu-mint-rest spring-boot:run
```

With the `dev` profile active, the REST app uses the preload-based `MintLoadService` that reads the JSON pointed to by `MINT_PRELOAD_JSON_INPUT` (default `scripts/preload-test-data.json`). In docker-compose, the `scripts` directory is mounted read-only into the container at `/app/scripts` and the env var is set accordingly, so `/v1/keysets` works out of the box. In production (no `dev`/`test` profile), it falls back to the default loader backed by the vault client. The REST module packages a standard Spring Boot entry point and Maven plugin configuration, so this command compiles the protocol module, starts `CashuMintRestApplication`, and wires the controllers discussed in the module reference (see [`cashu-mint-rest/pom.xml`](../../cashu-mint-rest/pom.xml) and [`CashuMintRestApplication.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java)).

## Iterate with clients or admin tools

Use the `CashuClient` helpers to script manual tests against the running REST service without duplicating HTTP plumbing or lifecycle rules (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java)). Admin domain references have moved to the separate admin project at `../cashu-mint-admin`.
