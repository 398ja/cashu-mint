# Getting started

This tutorial walks you through starting a local Cashu mint for development.

## Prerequisites

- **Java 21** — verify with `java -version`. The Maven Enforcer plugin rejects other versions.
- **Maven 3.x** — the included `./mvnw` wrapper works if Maven is not installed globally.
- **Docker & Docker Compose** — required for the development stack (PostgreSQL, vault, gateway).

## 1. Clone the repository

```bash
git clone https://github.com/398ja/cashu-mint.git
cd cashu-mint
```

## 2. Build and run unit tests

```bash
mvn clean verify
```

This compiles every module, runs unit tests, and packages the artifacts. On a fresh clone the build downloads dependencies, so the first run takes longer.

## 3. Start the development stack

The dev compose file starts PostgreSQL, the vault, a phoenixd mock, a payment adapter, and the mint itself:

```bash
docker compose -f docker-compose.dev.yml up -d
```

Wait for all containers to become healthy:

```bash
docker compose -f docker-compose.dev.yml ps
```

## 4. Verify the mint is running

Query the NUT-06 info endpoint:

```bash
curl -s http://localhost:7777/v1/info | jq .
```

You should see the mint name, version, supported NUTs, and contact information.

## 5. Create a mint quote

Confirm gateway connectivity by requesting a mint quote for 100 sats:

```bash
curl -s -X POST http://localhost:7777/v1/mint/quote/bolt11 \
  -H 'Content-Type: application/json' \
  -d '{"amount": 100, "unit": "sat"}' | jq .
```

The response contains a `quote` id and a Lightning invoice (`request` field). In dev mode the phoenixd mock accepts any payment, so you can proceed to mint tokens immediately.

## 6. Tear down

```bash
docker compose -f docker-compose.dev.yml down
```

## Next steps

- [Run tests](../how-to/run-tests.md) — unit, integration, and E2E tests
- [Configure the mint](../how-to/configure-mint.md) — override properties and gateway mappings
- [Architecture overview](../explanations/architecture-overview.md) — understand the component layout
