# Run E2E tests

This guide explains how to run the end-to-end test suite for the admin module.

## Overview

E2E tests live in `cashu-mint-admin/mint-admin-tests/e2e-tests`. They exercise the
full stack — mint REST API, admin REST API, vault, HashiCorp Vault, and payment
gateway — to verify the components work together, and they are the only tests that
assert admin actions against the mint rather than against the admin's own response.

The suite **starts its own stack**. Testcontainers brings up
`src/test/resources/compose/e2e-stack.yml` with dynamic port mapping, waits for
health, and tears it down afterwards. You do not need to start the dev stack; in
fact the dev stack binds the same host ports, so stop it first if it is running.

## Prerequisites

- Java 21 with `JAVA_HOME` set.
- Docker running.
- The images the stack references, built from this reactor. The `e2e-tests` profile
  builds them, and the vault image must match the `cashu-vault.version` in the root
  POM — if that version is not published, build it locally from the `cashu-vault`
  repository with `./mvnw jib:dockerBuild -pl cashu-vault-jpa`.

## Run the tests

```bash
./mvnw verify -Pe2e-tests -pl cashu-mint-admin/mint-admin-tests/e2e-tests -am
```

Each flag matters:

- `-Pe2e-tests` sets `e2e.image.skip=false`, so jib builds the mint and admin images
  the compose stack runs. Without it the stack starts on stale images, or fails to
  find them at all.
- `-am` builds the modules the test module depends on. Without it the reactor cannot
  resolve `cashu-mint-admin` and the build fails before any test runs.

To run a single class:

```bash
./mvnw verify -Pe2e-tests -pl cashu-mint-admin/mint-admin-tests/e2e-tests -am \
  -Dit.test=KeyRotationE2EIT
```

## Running against an already-started stack

Set `E2E_USE_EXTERNAL=true` to skip container startup and connect to a stack you
started yourself on fixed ports (admin 7778, mint 7777). Useful when you want to
inspect the containers after a failure, since the managed stack is torn down.

```bash
docker compose -f docker-compose.dev.yml up -d
E2E_USE_EXTERNAL=true ./mvnw verify -Pe2e-tests \
  -pl cashu-mint-admin/mint-admin-tests/e2e-tests -am
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Could not resolve dependencies … cashu-mint-admin` | `-am` omitted | Add `-am` |
| `compose up -d` exits 1 immediately | An image the stack references does not exist locally or in the registry | Check `cashu-vault.version` in the root POM resolves to a real image; build it locally if not |
| Tests skipped, build passes | `-Pe2e-tests` omitted | Add the profile |
| Port already in use | The dev stack is running on the same ports | `docker compose -f docker-compose.dev.yml down` |
| Container startup timeout (8 min) | Docker resource limits | Increase Docker memory/CPU |
| `429 rate limited` on `/api/v1/auth/init` | A test re-authenticated per assertion | Hold one signed-in client per test class rather than calling `adminApiClient()` in a poll |

## See also

- [Run tests](run-tests.md) — unit and integration test guides
- [Getting started](../tutorials/getting-started.md) — setting up the dev environment
