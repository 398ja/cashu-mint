# Run E2E tests

This guide explains how to run the end-to-end test suite for the admin module.

## Overview

E2E tests live in `cashu-mint-admin/mint-admin-tests/e2e-tests`. They exercise the full stack — mint REST API, admin REST API, vault, and payment gateway — to verify that components work together correctly.

The tests use Testcontainers and JUnit 5 via the Maven Failsafe plugin.

## Prerequisites

- Java 21 with `JAVA_HOME` set
- Docker running (Testcontainers spins up service containers)
- The project built at least once (`mvn clean install -DskipTests`)

## Start the dev stack

The E2E tests require the development services to be running:

```bash
docker compose -f docker-compose.dev.yml up -d
```

Wait for all containers to report healthy:

```bash
docker compose -f docker-compose.dev.yml ps
```

## Run the tests

```bash
mvn clean verify -pl cashu-mint-admin/mint-admin-tests/e2e-tests
```

This runs the Failsafe plugin which picks up `**/*IT.java` test classes.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Connection refused | Dev stack not running | Start it with `docker compose -f docker-compose.dev.yml up -d` |
| Container startup timeout | Docker resource limits | Increase Docker memory/CPU allocation |
| Stale data between runs | Previous test data persisted | Restart the dev stack: `docker compose -f docker-compose.dev.yml down && docker compose -f docker-compose.dev.yml up -d` |

## See also

- [Run tests](run-tests.md) — unit and integration test guides
- [Getting started](../tutorials/getting-started.md) — setting up the dev environment
