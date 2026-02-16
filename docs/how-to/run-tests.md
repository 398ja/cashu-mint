# Run tests

This guide explains how to execute the different test suites for the project.

## Prerequisites

- Java 21 installed with `JAVA_HOME` set
- Maven 3.x (or use the `./mvnw` wrapper)
- Docker running (for integration and E2E tests)

## Unit tests

Unit tests run on every build by default. They are fast, isolated, and use Mockito for mocking.

```bash
mvn clean verify
```

### Running a single test class

```bash
mvn test -Dtest=SwapTaskTest -pl cashu-mint-protocol
```

### Running a single test method

```bash
mvn test -Dtest=SwapTaskTest#shouldSwapTokens -pl cashu-mint-protocol
```

### Running tests in a specific module

```bash
mvn test -pl cashu-mint-rest
```

## Integration tests

Integration tests live in `cashu-mint-rest-it` and are excluded by the default Maven profile. They test component interactions using Spring Boot context and Testcontainers.

```bash
mvn clean verify -Pintegration-tests
```

This activates the `integration-tests` profile which runs the Failsafe plugin against `**/*IT.java` files.

## E2E tests

End-to-end tests are in the `cashu-mint-admin` submodule under `mint-admin-tests/e2e-tests`. They require the full dev stack to be running.

```bash
# Start the dev stack first
docker compose -f docker-compose.dev.yml up -d

# Run E2E tests
mvn clean verify -pl cashu-mint-admin/mint-admin-tests/e2e-tests
```

See [Run E2E tests](run-e2e-tests.md) for a more detailed guide.

## Code coverage

Generate a JaCoCo coverage report:

```bash
mvn clean verify jacoco:report
```

View the report at `target/site/jacoco/index.html` in each module. The project targets 80%+ line coverage for unit tests.

## Common failures

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Java version mismatch` | Wrong JDK active | Set `JAVA_HOME` to JDK 21 |
| Integration tests timeout | Docker services not running | Run `docker compose -f docker-compose.dev.yml up -d` |
| Port already in use | Another process on 7777/3333/8080 | Stop the conflicting process or change ports via env vars |
| Mockito inline-mock-maker warning | Known Java 21 issue | Harmless — tests still pass |
| Preload data missing | Test data not generated | Run `./mvnw -q -pl cashu-mint-tools -Ppreload-all validate` |

## See also

- [Getting started](../tutorials/getting-started.md)
- [Configuration](../reference/configuration.md)
