# Add a NUT implementation

This guide walks through the steps to implement a new Cashu NUT (Notation, Usage & Terminology) specification in the mint.

## 1. Read the specification

Every NUT has an official spec at `https://github.com/cashubtc/nuts/blob/main/{NN}.md`. Read it thoroughly before writing code — the spec defines request/response formats, error codes, and edge cases.

## 2. Create the NUT class

Add a new static class in the protocol module:

```
cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT{NN}.java
```

Follow the existing pattern: each public method is a static protocol operation that accepts domain objects and returns a result. The NUT class should contain no Spring dependencies — it is pure protocol logic.

## 3. Create task classes

If the NUT involves a multi-step workflow (e.g., verify quote, check fees, sign messages), encapsulate each step in a task:

```
cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/{TaskName}Task.java
```

Tasks promote testability and single responsibility. Look at `MintTokensTask`, `SwapTask`, or `MeltTask` for examples.

## 4. Add a REST endpoint

If the NUT requires a new HTTP endpoint, add a controller in the REST module:

```
cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/controller/
```

The controller delegates to the NUT class and returns the appropriate HTTP response. Use Spring's `@RestController` and `@RequestMapping` annotations.

## 5. Update mint.yaml

Add the new NUT to the supported capabilities in `cashu-mint-protocol/src/main/resources/mint.yaml`. This is what the `/v1/info` endpoint advertises to clients.

## 6. Write unit tests

Create tests for each task class:

```
cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/tasks/{TaskName}TaskTest.java
```

Every test method must have a comment describing its purpose. Use Mockito to mock vault and gateway SPIs. Target 80%+ line coverage.

## 7. Add integration tests

For end-to-end flows, add integration tests in `cashu-mint-rest-it`:

```
cashu-mint-rest-it/src/test/java/.../NUT{NN}IT.java
```

Integration tests use Testcontainers for PostgreSQL and WireMock for gateway stubs.

## Checklist

- [ ] Spec read and understood
- [ ] `NUT{NN}.java` protocol class created
- [ ] Task classes for complex workflows
- [ ] REST controller (if new endpoint needed)
- [ ] `mint.yaml` updated with new capability
- [ ] Unit tests with 80%+ coverage
- [ ] Integration tests for end-to-end flow
- [ ] `mvn clean verify` passes

## See also

- [Supported NUTs](../reference/nuts.md)
- [Architecture and NUTs](../explanations/architecture-and-nuts.md)
- [Module layers](../reference/module-layers.md)
