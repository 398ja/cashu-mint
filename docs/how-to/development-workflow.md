# Development workflow

This how-to guide walks through the daily development loop: preparing infrastructure, applying database migrations, running tests, and launching the REST service.

## Prerequisites

- Java 21 with `JAVA_HOME` configured so Maven can compile the modules (see [`pom.xml`](../../pom.xml)).
- Docker and Docker Compose for starting the Postgres databases and companion services.  
  There are separate Compose files for each environment:
    - [`docker-compose.dev.yml`](../../docker-compose.dev.yml) for development
    - [`docker-compose.prod.yml`](../../docker-compose.prod.yml) for production
    - See the relevant Compose file for your workflow in the project root.

## Start supporting services

1. Use the published vault image (default) or build it locally if you are changing vault code:
   ```bash
   # Default: pull the published image used by docker-compose.dev.yml
   docker pull docker.398ja.xyz/cashu-vault-jpa:0.10.1

   # Or build from a sibling checkout
   cd ../cashu-vault
   mvn clean package -DskipTests
   cd cashu-vault-jpa
   docker build -t docker.398ja.xyz/cashu-vault-jpa:latest .
   cd ../../cashu-mint
   ```

2. Start the databases and supporting services:
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```
   This starts:
   - `cashu-vault-db` and `payment-adapter-db`: PostgreSQL databases on ports 55433-55434
   - `cashu-vault-jpa`: The vault service with the API consumed by the mint; it migrates its own schema with Flyway at startup
   - `hashicorp-vault`: Holds the private keys the vault rows point at
   - `payment-adapter-rest`: Gateway service for BOLT11 handlers
   - Other supporting services (phoenixd-mock, admin-rest, etc.)

## Schema and seed data

Both are automatic. The vault service migrates its own schema with Flyway at
startup, and the mint's `VaultPreloadSeeder` seeds the keyset from
`scripts/preload-test-data.json` on first boot — the keyset row into the vault
database, the private keys into HashiCorp. Seeding is idempotent, so a restart
leaves an already-seeded vault alone.

To regenerate the preload data:

```bash
./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java
```

Seeding cannot be done by loading SQL: only the backend-aware vault client knows
where a private key goes. See [Tools reference](../reference/tools.md).

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

Which keysets the mint serves is decided by `MINT_PRELOAD_ENABLED`, not by the Spring profile. Left at its default (`true`), `PreloadMintLoadService` is `@Primary` and serves a fixed keyset straight from `MINT_PRELOAD_JSON_INPUT`. The dev stack sets it to `false` so the mint reads the shared vault instead, which is what makes a mint the admin provisions — and every rotation of its keyset — visible at `/v1/keysets`. The REST module packages a standard Spring Boot entry point and Maven plugin configuration, so this command compiles the protocol module, starts `CashuMintRestApplication`, and wires the controllers discussed in the module reference (see [`cashu-mint-rest/pom.xml`](../../cashu-mint-rest/pom.xml) and [`CashuMintRestApplication.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java)).

## Iterate with clients or admin tools

Use the `CashuClient` helpers to script manual tests against the running REST service without duplicating HTTP plumbing or lifecycle rules (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java)). Admin domain references have moved to the separate admin project at `../cashu-mint-admin`.
