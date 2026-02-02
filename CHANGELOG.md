# Changelog

All notable changes to the Cashu Mint will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.12.0] - 2026-02-02

### Security

- Completed Oracle Java Secure Coding Guidelines remediation tasks: final NUT/utility classes with private constructors, SHA-256 key derivation for lock keys, unmodifiable MintInfo nuts map, sanitized exception messages, webhook input validation, configurable swap/mint limits, WebSocket subscription limits, and NUT security Javadoc

### Changed

- Moved the Java secure coding audit report into the `audits/` directory

### Fixed

- Aligned `VerifyProofsTaskTest` voucher secret FQCN assertion with the current `nut18` package

---

## [0.11.1] - 2026-01-31

### Fixed

- **NUT-17 Info Endpoint**: Fixed NUT-17 WebSocket configuration not appearing in `/v1/info` response
  - Spring's `@ConfigurationProperties` binding could not correctly handle complex nested list structures bound to `Object` type fields
  - Added direct YAML loading via SnakeYAML for NUT-17 configuration
  - Controller now explicitly serializes nuts map using getter to ensure dynamically loaded NUT-17 is included
  - Added unit test to verify NUT-17 support in mint info

---

## [0.11.0] - 2026-01-28

### Added

- **NUT-17 WebSocket Subscriptions**: Real-time notifications for proof and quote state changes
  - WebSocket endpoint at `/v1/ws` with JSON-RPC 2.0 protocol
  - `SubscriptionManager` for session tracking and efficient pub/sub with indexing
  - `Nut17WebSocketHandler` for JSON-RPC message handling (subscribe/unsubscribe)
  - `ProofStatePublisher` for broadcasting proof state transitions (UNSPENT → PENDING → SPENT)
  - `QuoteStatePublisher` for broadcasting quote state transitions (UNPAID → PAID → ISSUED)
  - `Nut17EventPublisher` service for publishing events from controllers
  - Spring ApplicationEvent integration for decoupled event propagation
  - Configurable via `cashu.websocket.enabled` property
  - Security warning logged at startup when wildcard origins (`*`) used in production

- **NUT-17 Protocol Implementation**: `NUT17.java` static utility class
  - Subscription parameter validation
  - Subscription ID generation
  - JSON-RPC response and notification factory methods
  - Filter ID extraction utilities

- **NUT-17 Integration Tests**: `Nut17WebSocketIT` with comprehensive test coverage
  - WebSocket connection establishment
  - Subscribe/unsubscribe command handling
  - Current state notification on subscription
  - Proof state change notifications
  - Multiple subscriber notification delivery

- **NUT-17 Unit Tests**: Event publisher test coverage
  - `ProofStatePublisherTest` for proof state event handling
  - `QuoteStatePublisherTest` for quote state event handling
  - `Nut17EventPublisherTest` for event emission via ApplicationEventPublisher

- **Virtual Thread Guidelines**: Comprehensive `CLAUDE.md` documentation
  - Decision table for when to use Virtual Threads
  - Code patterns for parallel I/O with CompletableFuture
  - Anti-patterns to avoid (synchronized on I/O, platform thread pools)
  - Configuration reference for VT-related components

### Changed

- Updated `mint.yaml` to advertise NUT-17 WebSocket subscription support
- Updated cashu-lib dependency from 0.13.1 to 0.14.0 (includes NUT-17 DTOs)
- Updated cashu-wallet dependency from 0.6.1 to 0.6.3
- `SubscriptionManager.sendCurrentState()` now uses Virtual Threads for parallel I/O
  - Parallel vault queries for proof state lookups
  - Parallel gateway queries for mint/melt quote state lookups
  - Improves performance when subscribers watch multiple items
- `WebSocketConfig` allowed-origins split now handles whitespace around commas
- Removed redundant `@ConditionalOnProperty` from `WebSocketConfig` bean method
- NUT-17 quote state payloads now enriched with additional fields from quote lookups
  - Mint quote notifications include `amount`, `request`, and `expiry`
  - Melt quote notifications include `amount` and `expiry`

### Fixed

- Race condition in `SubscriptionManager.subscribe()` using atomic `compute()` operation

---

## [0.10.2] - 2026-01-28

### Added

- Request tracing and logging for `/swap` endpoint with correlation IDs for easier debugging
- Idempotent proof handling in `InvalidateProofsTask` to safely handle duplicate invalidation requests

### Changed

- Updated cashu-lib dependency from 0.13.0 to 0.13.1

---

## [0.10.1] - 2026-01-26

### Changed

- Aligned all child module versions to 0.10.1 (previously at 0.9.0)
- Updated cashu-vault dependency to 0.5.0

---

## [0.10.0] - 2026-01-26

### Added

- **Proof Locking for Double-Spend Prevention**: `SwapTask` now uses per-proof locking via `ProofLockManager`
  - Serializes concurrent swap requests for the same proofs
  - Prevents double-spend attacks at the application level
  - Allows parallel swapping of different proof sets
  - Complements database-level unique constraints in cashu-vault

- **Concurrency Tests for SwapTask**: Comprehensive test suite in `SwapTaskConcurrencyTest`
  - Tests serialization of same-proof swaps
  - Tests parallel execution of different-proof swaps
  - Tests double-spend prevention behavior
  - Tests partial overlap handling

### Changed

- Updated nostr-java dependency from 1.2.1 to 1.3.0
- Updated cashu-lib dependency from 0.12.0 to 0.13.0
- Updated cashu-vault dependency from 0.4.6 to 0.5.0

### Fixed

- **Webhook Cache TTL**: Added TTL-based cache eviction to `QuoteStatusUpdater`
  - Prevents unbounded memory growth from completed payments
  - Configurable via `webhook.cache.ttl` property

---

## [0.9.0] - 2026-01-25

### Added

- **Webhook-Based Payment Notifications**: New `cashu-mint-webhook` module for push-based payment status
  - `PaymentWebhookController` receives payment events at `/webhook/payment`
  - `QuoteStatusUpdater` maintains in-memory cache for instant payment lookups
  - `WebhookSignatureValidator` validates HMAC-SHA256 signatures from `X-Webhook-Signature` header
  - `PaymentNotification` DTO with idempotency key generation for deduplication
  - Health endpoint at `/webhook/health` with cache statistics

- **PaymentStatusChecker Interface**: New abstraction in `cashu-mint-protocol` for payment verification
  - `isPaid(quoteId)` for instant cache lookup
  - `getPreimage(quoteId)` to retrieve payment proof
  - `markConsumed(quoteId)` for cleanup after successful minting
  - `MintTask` checks webhook cache first, falls back to gateway polling

- **Comprehensive Webhook Tests**: Unit and integration test coverage
  - `PaymentWebhookControllerTest`, `QuoteStatusUpdaterTest`, `WebhookSignatureValidatorTest`
  - `PaymentWebhookIT`, `PaymentWebhookE2EIT` for end-to-end testing

- **Payment Webhook Documentation**: `docs/explanations/payment-webhook-architecture.md`
  - Architecture comparison (polling vs push-based)
  - Configuration and benefits

### Changed

- `MintTask` now checks `PaymentStatusChecker` before polling gateway, reducing latency for cached payments
- `cashu-mint-rest` depends on `cashu-mint-webhook` module

---

## [0.8.0] - 2026-01-23

### Added

- **Virtual Thread Support (Project Loom)**: Full implementation for Java 21+ runtime optimization
  - Enable via `spring.threads.virtual.enabled=true` (default) or `SPRING_THREADS_VIRTUAL_ENABLED` env var
  - Virtual threads handle all request processing and async tasks
  - Per-quote locking with `QuoteLockManager` for parallel quote processing
  - Per-proof locking with `ProofLockManager` for parallel melt operations
  - Double-mint detection in `DefaultSignatureVaultService` as safety net

- **Lock Observability**: Prometheus metrics for lock contention monitoring
  - `cashu_mint_lock_wait_seconds` - time spent waiting to acquire locks
  - `cashu_mint_lock_hold_seconds` - time spent holding locks
  - `cashu_mint_lock_active` - current number of held locks
  - `LockMetrics`, `MicrometerLockMetricsAdapter` in observability module

- **Virtual Threads Grafana Dashboard**: New `cashu-mint-virtual-threads.json` dashboard
  - Lock contention panels (wait time, hold time, active locks)
  - JVM thread metrics (live threads, thread states)
  - Tomcat connection pool monitoring

- **Gateway Client Optimization**: JDK HttpClient with virtual thread executor
  - `GatewayClientConfiguration` provides `gatewayRestTemplate` bean
  - Configurable timeouts: `GATEWAY_CLIENT_CONNECT_TIMEOUT`, `GATEWAY_CLIENT_READ_TIMEOUT`

- **VT Operational Runbook**: `docs/runbooks/virtual-thread-issues.md`
  - Diagnosis and resolution for lock contention, pinning, memory leaks
  - Rollback procedure and escalation path

- **Load Testing Infrastructure**: k6 scripts and baseline metrics capture
  - `scripts/load-test-mint.js` for performance testing
  - Heap exhaustion testing for VT workloads

### Changed

- Updated cashu-lib dependency from 0.11.1 to 0.12.0
- Updated cashu-wallet dependency from 0.4.4 to 0.5.0
- Tomcat thread pool reduced to 50 max threads (VTs handle concurrency)
- Tomcat connection limits set to 2000 max-connections (primary VT concurrency limit)
- `AsyncConfig` configures virtual thread executor for `@Async` tasks

### Deprecated

- `ThreadUtil.MINT_MELT_LOCK` - replaced by `QuoteLockManager.lockQuote()` for per-quote locking

### Fixed

- Voucher arbitrary denominations now work correctly for minting and swaps

---

## [0.7.3] - 2026-01-21

### Changed

- Renamed payment-gateway dependencies to payment-adapter (0.6.0)
- Updated docker-compose service names from gateway to adapter

### Fixed

- Voucher issuance responses now include a generated `cashuA` token so REST clients and Nostr integration tests receive non-null voucher tokens.
- Integration test configuration now uses the token-enriching voucher service to keep Nostr voucher flows aligned with the REST behavior.

---

## [0.7.2] - 2026-01-15

### Changed

- Updated payment-gateway dependency to 0.5.0

---

## [0.7.1] - 2026-01-10

### Changed

- Updated cashu-lib dependency from 0.10.0 to 0.11.1
- Updated cashu-gateway dependency from 0.4.8 to 0.5.0
- Updated cashu-voucher dependency from 0.4.0 to 0.5.0
- Updated cashu-wallet dependency from 0.4.2 to 0.4.4
- Updated cashu-client dependency from 1.2.7 to 1.2.8

---

## [0.7.0] - 2026-01-07

### Added

- **Voucher Tag Support**: Enhanced voucher proof verification with tag-based identification
  - Enables voucher proofs to be identified and processed using NUT-10 tags
  - Supports flexible voucher detection across swap and verification operations

### Changed

- Bumped Spring Boot to 3.5.6, Tomcat to 10.1.48, and Logback to 1.5.19 to pick up upstream security fixes.
- Replaced deprecated Prometheus configuration flags with the current `management.prometheus.metrics.export.enabled` property and documented the new setting.

### Fixed

- Resolved Qodana findings across protocol and REST modules: removed redundant exception handling, enforced non-null blinded messages, improved refund signature logging, hardened YAML property loading, tightened preload SQL path validation, and cleaned up unused variables.
- Added Spring configuration metadata for Phoenixd, webhook, and voucher flags so test property files resolve cleanly.

---

## [0.6.0] - 2026-01-06

### Added

- **VoucherSpendingCondition**: New spending condition for voucher proof verification
  - Uses dynamic key derivation (same as minting) for arbitrary voucher amounts
  - Enables voucher proofs with non-power-of-2 amounts to be verified and swapped
- **Voucher Mock Payment**: Voucher tokens now skip Lightning payment verification during minting
  - Vouchers are merchant IOUs with no real bitcoin backing
  - `VoucherQuoteRegistry.isVoucherQuote()` detects voucher quotes in `MintTask`
  - Audit logging tracks when mock payment is used
- **Mixed Proof Type Validation**: `SwapTask` rejects operations mixing voucher and regular proofs
  - `VoucherSecretDetector.isVoucherSecret()` identifies voucher proofs
  - Clear error message: `mixed_proof_types_error`
- **Arbitrary Voucher Denominations (Free Splitting)**: Vouchers can use any positive amount
  - No power-of-2 denomination constraint for voucher tokens
  - Enables free splitting (e.g., 100 → 33 + 67) without swap overhead
  - `VoucherKeyDerivation` provides HMAC-SHA256 based key derivation for arbitrary amounts
  - `VoucherMasterSecretConfig` configures the voucher master secret
- New unit tests for voucher mock payment behavior:
  - `MintTaskTest`: voucher quote skip payment, regular quote requires payment, arbitrary denominations
  - `SwapTaskTest`: mixed proof rejection, voucher-only swaps, non-power-of-2 splits
- New documentation: `docs/explanations/voucher-mock-payment.md`

### Changed

- `MintTask` now branches on `isVoucherQuote` for payment verification and denomination validation
- `SwapTask` validates proof types before processing and allows arbitrary output amounts for voucher swaps
- `SignBlindedMessageTask` supports voucher mode with dynamic key derivation
- `VerifyProofsTask` routes voucher proofs to `VoucherSpendingCondition` for dynamic key verification

### Fixed

- Voucher proof verification now uses dynamic key derivation matching minting
  - Previously, voucher proofs with arbitrary amounts (e.g., 33 sats) failed verification
  - The vault had no stored key for non-power-of-2 amounts, causing `verify_proof_key_set_not_found`
  - Now uses `VoucherKeyDerivation` to derive keys on-the-fly during verification

---

## [0.5.2] - 2025-12-28

### Changed

- Updated cashu-voucher dependency from 0.3.6 to 0.3.7
- Updated cashu-wallet dependency from 0.4.0 to 0.4.2
- Updated cashu-client dependency from 1.2.6 to 1.2.7

---

## [0.5.1] - 2025-12-23

### Added

- Built-in task instrumentation via `TaskExecutionRecorder`/`InstrumentedTask`, enabling task-level metrics even when protocol tasks are instantiated directly.

### Fixed

- Enforce Model B voucher rejection during swap and melt verification with clear `CashuErrorException` messages and safer melt proof checks.

### Changed

- Test infrastructure: enable Mockito inline mock maker with ByteBuddy agent to support static/constructor mocks in CI-friendly environments.
- Updated cashu-voucher dependency from 0.3.5 to 0.3.6
- Updated cashu-client dependency from 1.2.5 to 1.2.6
- Updated nostr-java dependency from 1.0.1 to 1.1.0

---

## [0.5.0] - 2025-12-22

### Added

- NUT-12 support enabled across the mint, aligning protocol and REST handling with the updated specification.
- Mint now generates and attaches DLEQ proofs to blind signatures with tests covering proof generation and attachment.

### Changed

- Bumped project version to 0.5.0 across all modules.

---

## [0.4.7] - 2025-12-21

### Added

- New explanation page for voucher mint percentage fees overview (`docs/explanations/voucher-mint-quote-overview.md`).

### Changed

- Upgraded project version to 0.4.7 across all modules.
- Moved voucher percentage fee implementation plan to `project/` to keep user-facing docs concise.

### Fixed

- Voucher Nostr integration tests now use a matching BIP-340 public key for the configured private key.
- Mockito inline/agent wiring stabilized to allow static and constructor mocks in tests.

---

## [0.4.3] - 2025-12-17

### Fixed

- **Voucher Swap Support**: Allow voucher proofs (NUT-10 VOUCHER secrets) in swap operations
  - Removed incorrect Model B enforcement from `VerifyProofsTask` - swapping is not redemption
  - Model B enforcement (controlling where vouchers can be redeemed for goods/services) belongs at the merchant/application layer, not the protocol layer
  - Added `VoucherWellKnownSecret` handling in `getSpendingCondition()` using `RSSSpendingCondition` (same BDHKE verification)

- **Proof Storage**: Fixed `MintProtocolUtil.toProofEntity()` to store Y coordinate instead of raw secret string
  - Was storing `proof.getSecret().toString()` which returns full NUT-10 JSON (992+ characters for vouchers)
  - Now uses `SecretUtil.toY()` to store 66-character hex Y coordinate
  - Fixes "value too long for type character varying(255)" database errors

- **VoucherSecretDetector**: Enhanced detection to recognize all voucher secret formats
  - Added check for `VoucherWellKnownSecret` (NUT-10 format from cashu-lib-common)
  - Added check for `WellKnownSecret` with `Kind.VOUCHER`
  - Maintains reflection-based check for optional `VoucherSecret` from cashu-voucher-domain

### Changed

- Updated to cashu-lib 0.7.2 for NUT-10 BDHKE verification fix

---

## [0.4.2] and earlier

See git history for earlier changes.
