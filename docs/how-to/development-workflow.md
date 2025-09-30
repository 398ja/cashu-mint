# Development workflow

This how-to guide walks through the daily development loop: preparing infrastructure, applying database migrations, running tests, and launching the REST service.

## Prerequisites

- Java 21 with `JAVA_HOME` configured so Maven can compile the modules (see [`pom.xml`](../../pom.xml)).
- Docker and Docker Compose for starting the Postgres databases and companion services defined in [`docker-compose.yml`](../../docker-compose_bck.yml).

## Start supporting services

1. Build the vault Docker image (if not already built):
   ```bash
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
   - `cashu-vault-db` and `cashu-gateway-db`: PostgreSQL databases on ports 55433-55434
   - `vault-db-init`: Runs the vault schema migration (V1__init_schema.sql)
   - `vault-db-seed`: Seeds the vault with test data (preload-test-data.sql)
   - `cashu-vault-jpa`: The vault service with the API consumed by the mint
   - `cashu-gateway-rest`: Gateway service for BOLT11 handlers
   - Other supporting services (phoenixd-mock, admin-rest, etc.)

## Run database migrations

The `vault-db-init` and `vault-db-seed` services handle database schema and seeding automatically when using `docker-compose.dev.yml`:

1. `vault-db-init` runs the schema migration from `cashu-vault-jpa/src/main/resources/db/migration/V1__init_schema.sql`
2. `vault-db-seed` loads test data from `scripts/preload-test-data.sql`

These run automatically on startup. The seed script is idempotent - it only truncates and reloads data if the mint doesn't already exist.

### Manual seeding (optional)

If you need to regenerate or manually run the seed data:

```bash
# Generate new seed data
./mvnw -q -pl cashu-mint-tools -Ppreload-all validate

# Manually seed (if needed)
docker compose -f docker-compose.dev.yml exec -T cashu-vault-db \
  psql -U postgres -d cashu_vault -v ON_ERROR_STOP=1 < scripts/preload-test-data.sql
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
SPRING_PROFILES_ACTIVE=dev MINT_PRELOAD_JSON_INPUT=file:$(pwd)/scripts/preload-test-data.json \
  ./mvnw -pl cashu-mint-rest spring-boot:run
```

With the `dev` profile active, the REST app uses the preload-based `MintLoadService` that reads the JSON pointed to by `MINT_PRELOAD_JSON_INPUT` (default `scripts/preload-test-data.json`). In docker-compose, the `scripts` directory is mounted read-only into the container at `/app/scripts` and the env var is set accordingly, so `/v1/keysets` works out of the box. In production (no `dev`/`test` profile), it falls back to the default loader backed by the vault client. The REST module packages a standard Spring Boot entry point and Maven plugin configuration, so this command compiles the protocol module, starts `CashuMintRestApplication`, and wires the controllers discussed in the module reference (see [`cashu-mint-rest/pom.xml`](../../cashu-mint-rest/pom.xml) and [`CashuMintRestApplication.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java)).

## Iterate with clients or admin tools

Use the `CashuClient` helpers to script manual tests against the running REST service without duplicating HTTP plumbing or lifecycle rules (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java)). Admin domain references have moved to the separate admin project at `../cashu-mint-admin`.
