# Development workflow

This how-to guide walks through the daily development loop: preparing infrastructure, applying database migrations, running tests, and launching the REST service.

## Prerequisites

- Java 21 with `JAVA_HOME` configured so Maven can compile the modules.【F:pom.xml†L20-L21】
- Docker and Docker Compose for starting the Postgres databases and companion services defined in `docker-compose.yml`.【F:docker-compose.yml†L1-L195】

## Start supporting services

1. Start the databases used by the mint, vault, and gateway components:
   ```bash
   docker compose --profile dev up -d cashu-mint-db cashu-vault-db cashu-gateway-db
   ```
   These containers expose healthy Postgres instances on ports 55432–55434 for local development.【F:docker-compose.yml†L2-L60】
2. Bring up the dependent services that manage schema and gateway integrations:
   ```bash
   docker compose --profile dev up -d cashu-vault-jpa cashu-gateway-rest cashu-gateway-webhook
   ```
   The vault service applies its schema as it starts and exposes the API consumed by the mint; the gateway services wire REST calls to BOLT11 handlers with auto-DDL enabled for local use.【F:docker-compose.yml†L65-L200】

## Run database migrations

1. Ensure the `cashu-vault-jpa` container is running (see above). It initialises its database automatically before advertising readiness, so no extra Flyway/Liquibase command is required for the vault schema.【F:docker-compose.yml†L65-L99】
2. Optionally seed the mint database with deterministic test data:
   ```bash
   ./mvnw -q -pl cashu-mint-protocol exec:java \
     -Dexec.mainClass=xyz.tcheeric.cashu.mint.tools.MintPreloadDataGenerator \
     -Dexec.args="scripts/preload-test-data.json"
   ./scripts/render-preload-sql.sh scripts/preload-test-data.json scripts/preload-test-data.sql
   psql -d cashu_mint -f scripts/preload-test-data.sql
   ```
   The generator and renderer live in the protocol module; the helper script wraps the renderer invocation, and the README records the full workflow for convenience.【F:README.md†L13-L34】【F:scripts/render-preload-sql.sh†L1-L14】

## Run tests

Execute the full build from the repository root before opening a pull request:

```bash
mvn -q verify
```

The dedicated test guide contains additional context if you need to customise the command.【F:docs/how-to/run-tests.md†L1-L10】

## Launch the REST API

Start the Spring Boot application when you want to exercise endpoints locally:

```bash
./mvnw -pl cashu-mint-rest spring-boot:run
```

The REST module packages a standard Spring Boot entry point and Maven plugin configuration, so this command compiles the protocol module, starts `CashuMintRestApplication`, and wires the controllers discussed in the module reference.【F:cashu-mint-rest/pom.xml†L41-L68】【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java†L1-L48】

## Iterate with clients or admin tools

Use the `CashuClient` helpers or the administrative domain model to script manual tests against the running REST service without duplicating HTTP plumbing or lifecycle rules.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java†L12-L47】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/domain/MintAggregate.java†L1-L78】
