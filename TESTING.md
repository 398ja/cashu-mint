# Testing Guide for cashu-mint

This document describes the testing strategy and how to run different types of tests in the cashu-mint project.

## Table of Contents

- [Test Types](#test-types)
- [Running Tests](#running-tests)
- [Test Naming Conventions](#test-naming-conventions)
- [Maven Profiles](#maven-profiles)
- [Writing Tests](#writing-tests)

## Test Types

### 1. Unit Tests

Unit tests focus on testing individual components in isolation with minimal dependencies.

**Characteristics:**
- Fast execution (milliseconds)
- No external dependencies (databases, networks, etc.)
- Use mocking for dependencies
- Run on every build

**Examples:**
- `VerifyProofsTaskTest.java` - Tests proof validation logic
- Domain model tests

### 2. Integration Tests

Integration tests verify that multiple components work together correctly, often with external dependencies.

**Characteristics:**
- Slower execution (seconds to minutes)
- May require external services (Nostr relay, database, etc.)
- Test real component interactions
- Only run when explicitly requested

**Examples:**
- `VoucherNostrIT.java` - Tests Nostr integration with mocked dependencies
- Full Spring Boot context tests

**Naming Convention:** Must end with `IT.java` or `IntegrationTest.java`

## Running Tests

### Run All Unit Tests

Regular build runs only unit tests (excludes integration tests):

```bash
mvn clean test
```

### Run Integration Tests

To run integration tests, use the `integration-tests` profile:

```bash
# Run integration tests only
mvn clean verify -Pintegration-tests

# Run both unit and integration tests
mvn clean verify -P-skip-integration-tests,integration-tests
```

### Run Tests in Specific Module

```bash
# Unit tests only
mvn clean test -pl cashu-mint-rest

# Integration tests only
mvn clean verify -Pintegration-tests -pl cashu-mint-rest

# Specific test class
mvn test -Dtest=VerifyProofsTaskTest -pl cashu-mint-protocol
```

### Skip All Tests

```bash
mvn clean install -DskipTests
```

## Test Naming Conventions

### Unit Tests

- Format: `{ClassName}Test.java`
- Examples:
  - `VerifyProofsTaskTest.java`
  - `VoucherSecretTest.java`
  - `MerchantVerificationServiceTest.java`

### Integration Tests

- Format: `{Feature}IT.java` or `{Feature}IntegrationTest.java`
- Examples:
  - `VoucherNostrIT.java`
  - `VoucherControllerIT.java`
  - `DatabaseMigrationIT.java`

## Maven Profiles

### skip-integration-tests (Active by Default)

Excludes integration tests from regular builds.

**Configuration:**
```xml
<excludes>
    <exclude>**/*IT.java</exclude>
    <exclude>**/*IntegrationTest.java</exclude>
</excludes>
```

**When to use:**
- Regular development builds
- CI/CD unit test stage
- Fast feedback loop during development

### integration-tests (Disabled by Default)

Runs integration tests using maven-failsafe-plugin.

**Configuration:**
```xml
<includes>
    <include>**/*IT.java</include>
    <include>**/*IntegrationTest.java</include>
</includes>
```

**When to use:**
- Before committing major changes
- CI/CD integration test stage
- Pre-release verification

## Writing Tests

### Unit Test Example

```java
@Test
public void testVoucherValidation() {
    VoucherSecret secret = VoucherSecret.create("issuer", "sat", 1000L, null, null);
    SignedVoucher voucher = new SignedVoucher(secret, signature, pubkey);

    assertTrue(voucher.isValid());
}
```

### Integration Test Example

```java
@SpringBootTest
@ActiveProfiles("test")
class VoucherNostrIT {

    @MockBean
    private VoucherLedgerPort voucherLedgerPort;

    @Test
    void testVoucherPublishing() {
        // Test with mocked Nostr components
        when(voucherLedgerPort.publish(...)).thenReturn(...);
        // ... test logic
    }
}
```

### Test Configuration

Integration tests use the `test` profile with configuration in:
```
src/test/resources/application-test.properties
```

**Key test configurations:**
```properties
# Voucher test configuration
voucher.enabled=true
voucher.mint.issuerPrivateKey=0000...0001
voucher.mint.issuerPublicKey=0279...81798
voucher.nostr.relays[0]=ws://localhost:7777
```

## CI/CD Integration

### GitHub Actions Example

```yaml
# Unit tests (fast feedback)
- name: Run Unit Tests
  run: mvn clean test

# Integration tests (thorough verification)
- name: Run Integration Tests
  run: mvn clean verify -Pintegration-tests
```

### Pre-commit Hook Example

```bash
#!/bin/bash
# Run unit tests before allowing commit
mvn clean test -q
if [ $? -ne 0 ]; then
    echo "Unit tests failed. Commit rejected."
    exit 1
fi
```

## Test Coverage

The project uses JaCoCo for code coverage analysis.

### Generate Coverage Report

```bash
mvn clean verify jacoco:report
```

### View Coverage Report

```bash
open target/site/jacoco/index.html
```

### Coverage Targets

- **Unit Tests**: 80%+ line coverage
- **Integration Tests**: Focus on critical paths
- **Overall**: 75%+ line coverage

## Troubleshooting

### Integration Tests Fail Locally

**Problem:** Integration tests fail with connection errors.

**Solution:** Ensure required services are running:
```bash
# Start Nostr relay (if testing with real relay)
docker run -p 7777:8080 scsibug/nostr-rs-relay

# Or use mocked components (default)
```

### Tests Run Slowly

**Problem:** Regular builds are slow due to integration tests.

**Solution:** Integration tests are excluded by default. If they're running:
```bash
# Verify profile is active
mvn help:active-profiles

# Ensure integration tests are skipped
mvn clean test -P-integration-tests
```

### MockBean Deprecation Warnings

**Problem:** Warnings about `@MockBean` deprecation.

**Solution:** This is a Spring Boot 3.5+ deprecation warning. The tests still work correctly. Future versions will migrate to new mocking approaches.

## Additional Resources

- [Maven Surefire Plugin](https://maven.apache.org/surefire/maven-surefire-plugin/) - Unit test execution
- [Maven Failsafe Plugin](https://maven.apache.org/surefire/maven-failsafe-plugin/) - Integration test execution
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
