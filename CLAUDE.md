# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java implementation of the Cashu ecash protocol, organized as a multi-module Maven project. The codebase follows Clean Architecture and Hexagonal Architecture principles with clear separation between protocol logic, persistence, and API layers.

**Main modules:**
- `cashu-mint-protocol` - Core Cashu protocol implementation (business logic) + port interfaces for durable persistence (`ports/MintQuoteRepository`, `IssuanceRecordRepository`, `WebhookEventRepository`)
- `cashu-mint-jpa` - PostgreSQL + Hibernate Envers adapter for the spec 001 durable quote / issuance / webhook tables. Activated via `cashu.mint.jpa.enabled=true`; ships Flyway migrations under `db/migration/V20260522_*.sql`
- `cashu-mint-rest` - Public REST API (port 7777)
- `cashu-mint-webhook` - Webhook-based payment notifications, durable `(provider, provider_event_id)` idempotency under spec 001 US2
- `cashu-mint-rest-it` - Integration tests
- `cashu-mint-tools` - Test data generation utilities
- `cashu-mint-observability` - Prometheus metrics, Grafana dashboards, health indicators

**External modules** (separate repository at `../cashu-mint-admin`):
- Admin functionality has been split to a separate project
- Referenced in docker-compose via published images

## Build Commands

**Requirements:** Java 21, Maven 3.x

```bash
# Build with unit tests (default)
mvn clean verify

# Run integration tests
mvn clean verify -Pintegration-tests

# Run specific test
mvn test -Dtest=SwapTaskTest -pl cashu-mint-protocol

# Run specific module
mvn test -pl cashu-mint-rest

# Skip tests entirely
mvn clean install -DskipTests

# Generate code coverage report
mvn clean verify jacoco:report
# View at: target/site/jacoco/index.html

# Run REST API locally
mvn -q -pl cashu-mint-rest spring-boot:run

# Generate test data (preload database)
./mvnw -q -pl cashu-mint-tools -Ppreload-all validate
```

## Development Environment

**Start development stack:**
```bash
# Start all services (PostgreSQL, vault, gateway, mint)
docker compose -f docker-compose.dev.yml up -d

# Check service health
docker compose -f docker-compose.dev.yml ps

# View logs
docker compose -f docker-compose.dev.yml logs -f cashu-mint-rest-dev

# Seed test data into vault database
docker compose exec -T cashu-vault-db psql -U postgres -d cashu_vault < scripts/preload-test-data.sql

# Stop services
docker compose -f docker-compose.dev.yml down
```

**Key ports:**
- Mint REST API: 7777
- Gateway API: 8080
- Vault API: 3333
- Vault PostgreSQL: 55433
- Gateway PostgreSQL: 55434

## Architecture

### Layered Structure

The codebase uses dependency inversion with clear boundaries:

```
REST Controllers (cashu-mint-rest)
    ↓
NUT APIs (cashu-mint-protocol/nut/)
    ↓
Tasks (cashu-mint-protocol/tasks/)
    ↓
Services (cashu-mint-protocol/service/) and Ports (cashu-mint-protocol/ports/)
    ↓
Vault/Gateway SPIs + Spec-001 repository ports
    ↓
Infrastructure Adapters (cashu-vault-jpa, cashu-mint-jpa, payment-adapter-*)
```

### Spec 001 — Mint Quote and Webhook Integrity

The `cashu-mint-jpa` module (added in spec 001) hosts three append-only / audit-tracked tables in PostgreSQL:

- `mint_quote` (Envers-audited) — durable NUT-04 quote authorisations with a state machine `UNPAID → PENDING → PAID → ISSUING → ISSUED` (+ `EXPIRED` / `FAILED`). Transitions go through compare-and-set on `lifecycle_state` (`MintQuoteJpaRepository#casLifecycle`).
- `issuance_record` — append-only ledger keyed by `quote_id` carrying the `outputs_hash` (SHA-256 over sorted `(amount, keyset_id, B_)`) and the signed `BlindSignature` list. Backs FR-001 daily invariant and NUT-19 idempotent replay.
- `webhook_event` — append-only delivery log keyed by `(provider, provider_event_id)`. Backs FR-005/006/008.

Activation is gated by `cashu.mint.jpa.enabled=true`. Unit-test contexts leave it `false` and the relevant protocol tasks fall back to the legacy in-process path. Production deploys flip the flag on along with the datasource and webhook-secret configuration documented in `docs/how-to/configure-webhook-integrity.md`.

### Spec 002 — Melt Path Burn-First Ordering and Saga State Machine

The same `cashu-mint-jpa` module (spec 002) adds two more tables in PostgreSQL plus a typed payment-outcome contract over the gateway:

- `melt_saga` (Envers-audited on `current_state` / `payment_hash` / `provider_event_id`) — durable NUT-05 melt attempt with a state machine `PROOFS_HELD → PAYMENT_SENT → COMPLETED` on the happy path, `FAILED` / `PAYMENT_SENT_BURN_FAILED` / `PAYMENT_UNKNOWN` on the compensation branches. Transitions go through compare-and-set on `current_state` (`MeltSagaJpaRepository#casState`).
- `melt_saga_transition` — append-only timeline keyed by `(melt_saga_id, seq)` carrying every state change including polling no-ops from the reconciler. Backs the admin query endpoint.

Three new abstractions in `cashu-mint-protocol/.../proto/`:

- `LightningPaymentPort` — typed wrapper around `payment-adapter`'s `Gateway.pay(quoteId)` that surfaces `PaymentOutcome.Success | DefinitiveFailure | Unknown` instead of the legacy boolean/throw signature. Strict-parse rule per FR-008: timeouts, 5xx, missing `payment_hash` → `Unknown` (saga lands in `PAYMENT_UNKNOWN`, never auto-resolves).
- `PaymentOutcome` (sealed type) — the three concrete cases. `MeltTask` switches on this exhaustively.
- `MeltSagaReconciler` (`@Scheduled` `@Component`) — polls `LightningPaymentPort.checkStatus` for `PAYMENT_UNKNOWN` sagas and sweeps stale `PROOFS_HELD` sagas. **MUST NEVER call `pay()`** (FR-007 compliance gate enforced by `MeltSagaReconcilerTest#reconciler_NEVER_calls_pay`).

`MeltTask` drives the saga: durable `PROOFS_HELD` + proof `PENDING` commit BEFORE `gateway.pay`, then switches on `PaymentOutcome` to advance to the terminal state. The `MintIntegrityContext` service-locator carries the spec-001 + spec-002 dependencies into the static `NUT04`/`NUT05` helpers; `MintIntegrityContextInstaller` populates it at Spring bootstrap.

Operator visibility: `MeltSagaAdminController` exposes `GET /admin/melt-saga/by-id|by-quote` plus a `POST /{id}/mark-resolved` action that appends a transition without overwriting `current_state`. Endpoint is conditional on the `MeltSagaRepository` bean; protect at the network layer until Spring Security service-account auth lands.

Configuration (`application.properties`):

```properties
cashu.mint.melt.payment-timeout=PT30S
cashu.mint.melt.reconcile-interval=PT60S
cashu.mint.melt.payment-unknown-ttl=PT1H
cashu.mint.melt.proofs-held-ttl=PT5M
```

Operator reconciliation queries are embedded as Javadoc on `MeltSagaJpaRepository` (SC-001 / SC-002 / SC-003 / SC-004 + a PAYMENT_SENT_BURN_FAILED hygiene query).

### NUT Implementation Pattern

Each Cashu specification (NUT) is implemented as a static class in `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/`:

- `NUT01.java` - Mint public key exchange
- `NUT02.java` - Keysets and keyset ID
- `NUT03.java` - Swap tokens
- `NUT04.java` - Mint tokens
- `NUT05.java` - Melt tokens
- `NUT06.java` - Mint information
- `NUT07.java` - Token state check
- `NUT09.java` - Restore signatures
- `NUT17.java` - WebSocket subscriptions (real-time state notifications)

**When implementing NUT features:**
1. Consult the official specification at https://github.com/cashubtc/nuts/blob/main/{NN}.md
2. Implement protocol logic in the corresponding NUT class
3. Delegate complex workflows to Task classes
4. Update mint.yaml if adding new capabilities

### Task-Based Orchestration

Multi-step workflows are encapsulated in task objects (`cashu-mint-protocol/tasks/`):
- `MintTokensTask`, `SwapTask`, `MeltTask` - Main operations
- `SignBlindedMessageTask`, `VerifyFeesTask` - Subtasks
- Tasks promote testability and single responsibility

### Key Domain Concepts

- **Mint** - The ecash issuer (identified by UUID)
- **KeySet** - Collection of cryptographic keys for different denominations
- **Proof** - Token ownership proof (amount, secret, C, keyset id)
- **BlindedMessage** - Blinded token request in minting process
- **Quote** - Payment request/response (mint quotes for receiving, melt quotes for spending)
- **Signature** - Mint's signature on blinded message proving authenticity
- **Voucher** - Gift card system using structured secrets and Nostr publishing

## Testing

### Test Types

**Unit tests** (`*Test.java` in `src/test/java`):
- Fast, isolated, use Mockito for mocking
- Run on every build by default
- Target: 80%+ line coverage

**Integration tests** (`*IT.java` or `*IntegrationTest.java` in `cashu-mint-rest-it`):
- Test component interactions
- Excluded by default, run with `-Pintegration-tests`
- May use Spring Boot context or Testcontainers

### Test Naming Rules

Unit tests: `{ClassName}Test.java`
Integration tests: `{Feature}IT.java` or `{Feature}IntegrationTest.java`

Every test method must have a comment describing its purpose.

### Maven Profiles

- `skip-integration-tests` - Active by default, excludes `**/*IT.java`
- `integration-tests` - Runs integration tests via Failsafe plugin
- `preload-json` - Generates test data JSON
- `preload-sql` - Renders SQL from JSON
- `preload-all` - Runs both generators

## Configuration

**Protocol configuration:** `cashu-mint-protocol/src/main/resources/proto.properties`
```properties
cashu.units=sat
cashu.expiry=15
gateway.bolt11.sat=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway
```

**Mint metadata:** `cashu-mint-protocol/src/main/resources/mint.yaml`
- Mint name, description, contact info
- Supported NUTs and payment methods
- Min/max amounts per method

**Environment variable overrides:**
- `CASHU_MINT_PORT` - API port (default: 7777)
- `GATEWAY_BOLT11_SAT` - Gateway implementation class
- `CASHU_VAULT_BASE_URL` - Vault service URL
- `PHOENIXD_BASE_URL` - Phoenixd Lightning service URL

## Code Standards

### Follow Clean Code Principles

From AGENTS.md, the codebase follows:
- "Clean Code" book chapters: 2, 3, 4, 7, 9 (testing), 10, 17
- "Clean Architecture" book parts III & IV, chapters 7-14
- Java Design Patterns from https://github.com/iluwatar/java-design-patterns
- Use Lombok to reduce boilerplate

### Use Virtual Threads for Concurrency

This project uses Java 21 Virtual Threads (Project Loom) for efficient concurrency. Virtual Threads are enabled by default via `spring.threads.virtual.enabled=true`. **Always prefer Virtual Threads over platform threads for I/O-bound work.**

#### When to Use Virtual Threads

| Scenario | Use Virtual Threads? | Pattern |
|----------|---------------------|---------|
| Database queries | Yes | `StructuredTaskScope` for parallel queries |
| HTTP/REST calls | Yes | `StructuredTaskScope` or VT executor |
| WebSocket message sends | Yes | Parallel send to multiple subscribers |
| File I/O | Yes | VT handles blocking efficiently |
| CPU-intensive computation | No | Use parallel streams or ForkJoinPool |
| Quick in-memory operations | No | Overhead not justified |

#### Patterns and Examples

**1. Parallel I/O with CompletableFuture and VT Executor (Preferred)**

Use when you need results from multiple independent I/O operations:

```java
import java.util.concurrent.*;

// Parallel database/API queries with Virtual Threads
private List<QuoteState> fetchQuoteStates(List<String> quoteIds, Gateway gateway) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<QuoteState>> futures = quoteIds.stream()
            .map(id -> CompletableFuture.supplyAsync(() -> {
                boolean paid = gateway.checkPaymentStatus(id);
                return new QuoteState(id, paid);
            }, executor))
            .toList();

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
            .map(f -> f.getNow(null))
            .filter(Objects::nonNull)
            .toList();
    }
}
```

**2. Fire-and-Forget with @Async**

Use for event handlers that shouldn't block the caller:

```java
@Async  // Runs on VT via AsyncConfig
@EventListener
public void onProofStateChange(ProofStateChangeEvent event) {
    subscriptionManager.publishProofState(event.getY(), event.getState(), null);
}
```

**3. Parallel Fan-Out (Notifications)**

Use when sending to multiple recipients:

```java
private void notifySubscribers(Set<String> subscriberIds, JsonRpcNotification notification) {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<Void>()) {
        for (String subId : subscriberIds) {
            scope.fork(() -> {
                WebSocketSession session = sessions.get(subId);
                if (session != null && session.isOpen()) {
                    session.sendMessage(new TextMessage(toJson(notification)));
                }
                return null;
            });
        }
        scope.join();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted during subscriber notification");
    }
}
```

**4. Simple Parallel Execution**

Use for straightforward parallelization without result collection:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    proofs.forEach(proof -> executor.submit(() -> {
        String y = SecretUtil.toY(proof.getSecret());
        publishProofState(y, NUT07.SPENT, null);
    }));
}  // Auto-waits for completion on close
```

#### Anti-Patterns to Avoid

**❌ Sequential I/O in loops when items are independent:**
```java
// BAD: Sequential blocking calls
for (String quoteId : quoteIds) {
    boolean paid = gateway.checkPaymentStatus(quoteId);  // Blocks
    // ...
}
```

**❌ Using synchronized for I/O operations (causes VT pinning):**
```java
// BAD: Pins virtual thread to carrier thread
synchronized (lock) {
    database.query(...);  // Pinned during entire I/O!
}

// GOOD: Use ReentrantLock instead
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    database.query(...);  // VT can unmount during I/O
} finally {
    lock.unlock();
}
```

**❌ Creating platform thread pools for I/O work:**
```java
// BAD: Wastes platform threads on I/O
ExecutorService pool = Executors.newFixedThreadPool(10);

// GOOD: Use virtual threads
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
```

#### Configuration Reference

| Component | Configuration | Purpose |
|-----------|--------------|---------|
| `AsyncConfig` | `@Async` executor | VT for all async tasks |
| `GatewayClientConfiguration` | HTTP client executor | VT for gateway calls |
| Tomcat | `spring.threads.virtual.enabled` | VT for request handling |
| `QuoteLockManager` | `ReentrantLock` | Per-quote locking (VT-safe) |
| `ProofLockManager` | `ReentrantLock` | Per-proof locking (VT-safe) |

#### Debugging Virtual Threads

```bash
# Enable VT debugging output
-Djdk.tracePinnedThreads=full

# Check for pinning in logs
grep -i "pinned" logs/application.log
```

See `docs/runbooks/virtual-thread-issues.md` for comprehensive troubleshooting.

### Commit Convention

**Use Conventional Commits** (enforced by GitHub Actions):
```
type(scope): description

Examples:
feat(nut): implement NUT-10 spending conditions
fix(protocol): correct signature verification in swap
chore(pom): update cashu-lib to 0.6.1
docs(how-to): add gateway configuration guide
test(protocol): add edge cases for melt validation
```

Types: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `style`, `perf`

### Code Quality Checks

GitHub Actions enforces:
- Conventional Commits format
- Google Java Format
- Unit tests must pass
- Java 21 compatibility

**Before committing:**
```bash
# Run from repository root
mvn -q verify

# Include output in PR description
# Mention if tests fail due to dependency/network issues
```

## Documentation

Documentation follows the **Diátaxis framework** (https://diataxis.fr/):

```
docs/
├── tutorials/          - Learning-oriented guides
├── how-to/            - Problem-solving guides
├── reference/         - Information-oriented specs
└── explanations/      - Understanding-oriented discussions
```

**When adding documentation:**
1. Classify as tutorial, how-to, reference, or explanation
2. Place in appropriate `docs/{section}/` directory
3. Start with `#` heading and purpose statement
4. Link from `docs/README.md`
5. Use relative links for cross-references
6. Keep code snippets minimal and tested

## Common Patterns

### Adding a New NUT Implementation

1. Create `NUT{NN}.java` in `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/`
2. Consult specification at https://github.com/cashubtc/nuts/blob/main/{NN}.md
3. Create task classes in `tasks/` for complex workflows
4. Add REST endpoint in `cashu-mint-rest` if needed
5. Update `mint.yaml` with new capability
6. Write unit tests for task classes
7. Add integration tests for end-to-end flows

### Working with Gateway Adapters

Gateways abstract payment methods (Lightning, etc.):
- Interface: `payment-adapter` module (external dependency)
- Implementation: `PhoenixdGateway` for Lightning BOLT11
- Test implementation: `DummyGateway` returns mock responses
- Configuration: `GATEWAY_{METHOD}_{UNIT}` environment variables

See `docs/how-to/configure-gateways.md` for details.

### Working with Vault Persistence

Vault abstracts proof/signature storage:
- Interface: `cashu-vault` module SPI
- Implementation: `cashu-vault-jpa` (PostgreSQL/H2)
- Flyway migrations in `cashu-vault-jpa/src/main/resources/db/migration`
- Test mode uses H2 in-memory database

### Voucher System

Vouchers use structured secrets with Nostr publishing:
- Domain: `cashu-voucher` module (external dependency)
- Format: JSON-encoded metadata in secret field
- Publishing: NIP-33 replaceable events to Nostr relays
- Controller: `VoucherController` in `cashu-mint-rest`
- Enable with `voucher.enabled=true`

## Module Dependencies

```
cashu-mint-rest
  ├── cashu-mint-protocol
  │     ├── cashu-lib (0.14.0)
  │     ├── cashu-vault (0.5.0)
  │     ├── payment-adapter (0.8.0)
  │     └── cashu-voucher (0.6.0)
  ├── cashu-mint-webhook
  └── Spring Boot 3.5.6

cashu-mint-webhook
  └── cashu-mint-protocol

cashu-mint-tools
  └── (independent, generates test data)

cashu-mint-rest-it
  ├── cashu-mint-rest
  ├── Testcontainers
  └── WireMock
```

**When updating ecosystem dependencies:**
- Maintain version properties in parent `pom.xml`
- Check compatibility with protocol implementation
- Update all modules consistently
- Run full test suite including integration tests

## Observability

The `cashu-mint-observability` module provides Prometheus metrics, health indicators, and Grafana dashboards.

### Quick Start

```bash
# Start mint with observability stack
docker compose -f docker-compose.dev.yml up -d
docker compose -f cashu-mint-observability/docker/docker-compose.observability.yml up -d

# Access dashboards
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin/admin)
```

### Key Metrics

| Metric | Description |
|--------|-------------|
| `cashu_mint_requests_total` | HTTP requests by endpoint/status |
| `cashu_mint_requests_duration_seconds` | Request latency histogram |
| `cashu_mint_proofs_issued_total` | Proofs issued |
| `cashu_mint_sats_outstanding` | Current liability (gauge) |
| `cashu_mint_task_duration_seconds` | Task execution time |
| `cashu_mint_quotes_active` | Active quotes by type |
| `cashu_mint_vouchers_fees_collected_total` | Voucher fees |

### Configuration

```properties
# Enable observability (default: true)
cashu.observability.enabled=true

# Enable task instrumentation
cashu.observability.tasks.enabled=true

# Enable voucher metrics
cashu.observability.vouchers.enabled=true

# Health indicators
cashu.observability.health.gateway.enabled=true
cashu.observability.health.vault.enabled=true
```

### Grafana Dashboards

Three pre-built dashboards in `cashu-mint-observability/docker/grafana/dashboards/`:
- **Cashu Mint Overview** - Health, request rate, outstanding sats
- **Cashu Mint Operations** - Task metrics, proof operations, HTTP details
- **Cashu Mint Business** - Quotes, vouchers, financial overview

See `cashu-mint-observability/docs/metrics-reference.md` for complete metrics documentation.

## SignatureVaultService

The mint exposes a shared `SignatureVaultService` bean to persist signatures across requests. When calling protocol methods like `NUT04.mint` or `NUT09.restore` directly, pass the `SignatureVaultService` instance to ensure signatures can be restored later.

## Special Directories

- `scripts/` - Build automation and SQL rendering helpers
- `docs/` - Diátaxis-organized documentation (32 files)
- `.github/workflows/` - CI/CD: tests, formatting, releases, conventional commits

## Docker

**Build images:**
```bash
# Build mint REST image
docker compose build cashu-mint-rest-dev

# Or use Jib from Maven
cd cashu-mint-rest && mvn jib:build
```

**Multi-stage Dockerfiles** are in each module's root directory.

**Published images** at `docker.398ja.xyz/cashu-mint-rest`

## Troubleshooting

**Integration tests fail locally:**
- Ensure Docker services are running: `docker compose -f docker-compose.dev.yml up -d`
- Check if required ports are available (7777, 3333, 8080)
- Verify test database is seeded with preload data

**Build fails with "Java version mismatch":**
- Ensure Java 21 is active: `java -version`
- Maven enforcer plugin requires Java 21

**Mockito warnings about inline-mock-maker:**
- This is a known warning with Java 21
- Tests still pass correctly
- Will be resolved in future Mockito releases

**Test data not loading:**
- Generate preload data: `./mvnw -q -pl cashu-mint-tools -Ppreload-all validate`
- Verify vault database is up before seeding
- Check `scripts/preload-test-data.sql` exists
