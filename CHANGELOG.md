# Changelog

All notable changes to the Cashu Mint will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.18.1] - 2026-05-24

### Fixed

- **PR #328 review follow-ups (spec 005 fail-closed path).**
  - Refund-failure recovery (Codex P1): if `refundForSaga` throws while
    releasing a partial hold, the saga is now left in `PROOFS_HELD` (not
    forced to `FAILED`) so `MeltSagaReconciler.sweepStaleProofsHeld` can
    retry the refund — forcing `FAILED` would strand the proofs in
    `PENDING` with no automatic recovery.
  - Consistent terminal error (Copilot): every bind-failure path
    (normalize error, vault exception, partial bind) now throws the
    `proofs_not_bound` terminal error rather than leaking
    `melt_proof_pending_error` / the raw vault cause.
  - Renamed `buildNormalisedProofEntities` / `normalisedProofs` to the
    American spelling used elsewhere in the codebase.
  - Tests assert the `proofs_not_bound` code on the vault-exception paths
    (unit + IT) and add a refund-failure-leaves-PROOFS_HELD case.

### Changed

- Updated cashu-vault 0.9.0 → 0.9.1 (insert-or-claim hardening).

---

## [0.18.0] - 2026-05-24

### Security

- **Spec 005 — Enforce durable melt-saga proof holds before external payment.**
  Closes the highest-priority finding in the 2026-05-24 backend token
  integrity review: the melt saga recorded `PROOFS_HELD` but the durable
  hold was not actually enforced before `lightningPaymentPort.pay`, so a
  melt could pay externally against zero or partially-bound proof rows.
  - `MeltTask` now performs a single atomic insert-or-claim per melt via
    the new `ProofVaultService.insertOrClaimForSaga`, after Y-normalising
    every proof through `ProofEntity.fromProof` (the canonical identity
    already used on the burn/SPENT path). This eliminates both the
    insert-then-claim no-op and the raw-secret/Y duplicate-row class.
  - **Fail-closed:** if not every submitted proof is durably bound (or the
    vault call throws), the saga releases any partial hold via
    `refundForSaga`, transitions `PROOFS_HELD → FAILED`, caches a terminal
    `proofs_not_bound` error, increments
    `cashu_mint_melt_proofs_not_bound_total`, and throws **before**
    `lightningPaymentPort.pay` is reached.
  - Requires cashu-vault 0.9.0 (additive `insertOrClaimForSaga` primitive).

### Added

- New client-facing error code `proofs_not_bound` (messages.properties).
- Metric `cashu_mint_melt_proofs_not_bound_total`.
- `MeltSagaProofsNotBoundIT` (4 cases: partial / zero / vault-exception bind
  all abort before payment; happy path proves bind-before-pay via the saga
  ledger) plus 3 new `MeltSagaStateMachineTest` unit cases.

### Deprecated

- `ProofVaultService.markPendingForSaga` — superseded by
  `insertOrClaimForSaga`. Retained as the underlying primitive used by the
  saga reconciler.

### Changed

- Updated cashu-vault 0.8.2 → 0.9.0.

---

## [0.17.0] - 2026-05-24

### Added

- **Spec 003 — Voucher Quote Durability and Funding-Source Binding** (FR-001
  through FR-013). Every voucher proof is now traceable to a durable
  `voucher_funding` row; the "skip payment check" loophole is closed.
  - Four new PostgreSQL tables in `cashu-mint-jpa`: `voucher_funding`
    (Envers-audited, JOINED-inheritance parent of `customer_payment_funding`
    / `merchant_debit_funding` / `merchant_iou_funding`), `voucher_quote`
    (Envers-audited, CAS-transitioned `UNFUNDED → FUNDED → ISSUING →
    ISSUED`), `voucher_issuance` (append-only ledger), and
    `voucher_idempotency_key` (DB-backed `Idempotency-Key` cache).
  - 8 protocol ports in `cashu-mint-protocol/.../proto/` for the voucher
    domain plus `VoucherFundingResolver` strategy + default impl that scans
    `webhook_event` for an `accepted` event and lazily creates a
    `CustomerPaymentFunding` row (idempotent on
    `(provider, provider_event_id)`).
  - `VoucherMintQuoteTask` persists a `voucher_quote` row at quote-creation
    time. `MintTask`'s voucher branch loads the durable record, runs the
    resolver, rejects with `funding_required` when no funding row resolves,
    then CAS-advances through `FUNDED → ISSUING → ISSUED` after signing.
  - `VoucherQuoteRegistry` demoted to a read-through cache; the durable
    repository is the source of truth.
  - REST hardening: `/v1/vouchers/**` now requires `ADMIN` role (FR-007),
    per-principal Caffeine rate-limit + 429 + `Retry-After` +
    `cashu_mint_voucher_rate_limit_breach_total` counter (FR-008),
    DB-backed idempotency replay + 409 tamper detection (FR-009), and a
    scheduled TTL prune for the idempotency cache.
  - Operator queries embedded as Javadoc on `VoucherIssuanceJpaRepository`
    (SC-001 orphan-issuance query + IOU liability dashboard).
- **Spec 004 — Voucher Data Minimisation and Customer-Identity Custody**
  (FR-001 through FR-019, plus Constitution Principle VII ratification
  `1.1.0 → 1.2.0`). Closes the data-custody gap spec 003 introduced — the
  mint no longer stores raw customer / merchant npubs.
  - **Hash at rest**: HMAC-SHA-256 via `javax.crypto.Mac`; salt from env
    `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes; boot fails closed
    otherwise). `IdentityHashConverter` applied via `@Convert` on every
    identity column on `voucher_quote`, `customer_payment_funding`,
    `merchant_debit_funding`, and `merchant_iou_funding`.
  - **Anonymous purchases**: `customer_id` nullable; null short-circuits
    the hasher so no enumerable hash-of-empty placeholder appears.
  - **Boot-time backfill**: `VoucherIdentityBackfillService` paginated
    (1000-row chunks, configurable), idempotent via
    `customer_id !~ '^[0-9a-f]{64}$'` filter. Updates live + Envers
    `_aud` rows in one transaction via `VoucherIdentityBackfillBatch`.
    `VoucherBackfillHealthIndicator` keeps `/actuator/health/readiness`
    DOWN until every required table has `completed_at IS NOT NULL`.
  - **Retention purge**: `VoucherIdentityRetentionPurgeService` daily
    `@Scheduled` (cron from `cashu.mint.voucher.identity-purge-cron`,
    default `0 0 3 * * *`). Nullifies identity columns on terminal-state
    rows past the retention boundary (default 90 days). Records audit row
    in `voucher_quote_purge_log` for the "purged" vs "anonymous"
    distinction.
  - **Idempotency cache scrub**: `IdentityFieldScrubber` walks JSON
    response bodies before they hit `voucher_idempotency_key.
    response_body_json`; hashes identity field values so a DB dump of
    the cache contains zero raw npubs.
  - **Forensic lookup** (FR-009): `POST /admin/voucher/forensic/
    customer-purchases` + `/merchant-purchases`. Operator submits raw
    npub; mint hashes internally; returns matching voucher_quotes. Salt
    never leaves the mint.
  - **Three new Grafana dashboards**: `voucher-liability-overview`,
    `voucher-token-integrity` (orphan-issuance gauge — SC-001 made
    glanceable), `voucher-iou-liability`. All run via a dedicated
    `cashu_mint_grafana_ro` PostgreSQL role with **column-level
    `GRANT SELECT` that excludes `customer_id` + `merchant_id`** —
    defence in depth on top of dashboard JSON review.
    `GrafanaRolePermissionIT` proves the DB-layer enforcement.
  - Customer-facing disclosure document at `docs/explanations/voucher-
    data-record.md`; CI test `DisclosureDocSchemaContractTest` fails the
    build on schema-vs-doc drift.

### Changed

- Bumped `cashu-mint` aggregator and all internal modules from `0.16.0`
  to `0.17.0`. No breaking API changes; additive only.
- `MintIntegrityContext` service-locator gained `.installVoucher()` +
  `.identityHasher()` accessors, extending the pattern from specs
  001/002/003 to the spec-004 surface.

### Security

- Customer + merchant npubs are now stored as HMAC-SHA-256 digests on
  every voucher-related table. A DB dump no longer reveals raw
  identifiers. Salt rotation in v1 is forward-only and explicitly
  accepts loss of pre-rotation forensic-lookup capability (see
  `specs/004-voucher-data-minimisation/quickstart.md` § 7).
- Grafana DB role `cashu_mint_grafana_ro` is provisioned with
  column-level grants that EXCLUDE identity columns — an ad-hoc
  Grafana query against `customer_id` fails at the PostgreSQL layer
  with `permission denied for column customer_id`.

### Fixed (PR #324 review round)

- `IdentityHashConverter` is now idempotent on already-hashed values
  (regex `^[0-9a-f]{64}$`). A JPA load → merge no longer double-hashes
  the stored HMAC, which would have broken every forensic lookup.
- `VoucherIdentityBackfillService` injects `IdentityHasher` directly
  instead of pulling from the static `MintIntegrityContext` at
  `@PostConstruct`; removes init-order race that could silently no-op
  the backfill.
- Per-batch backfill execution extracted to `VoucherIdentityBackfillBatch`
  so the `@Transactional` boundary is honoured by the Spring proxy
  (live + `_aud` UPDATEs commit together per research R9).
- `VoucherIdentityRetentionPurgeService` audit-table UPDATE now scopes
  by the live row's `lifecycle_state`; previously could purge `_aud`
  revisions of still-active UNFUNDED / FUNDED rows.
- Grafana datasource + Flyway placeholder now read the same env var
  (`CASHU_MINT_GRAFANA_RO_PASSWORD`) — previously the two diverged and
  silently broke dashboards.
- Prometheus backward-compat aliases switched from
  `metric_relabel_configs` (which REWROTE singular → plural and dropped
  the singular) to recording rules in `alerts.yml` (which CREATE the
  plural alongside the live singular).
- Alertmanager spec=004 routes moved BEFORE the severity catch-alls so
  the first-match-wins router actually reaches the voucher-pagerduty /
  voucher-slack-* receivers.
- `quickstart.md` § 7: replaced fictional `rotate-identity-salt`
  command with the actual v1 forward-only rotation runbook.

### Migration notes

- The new voucher / data-minimisation behaviour is gated on
  `cashu.mint.jpa.enabled=true`. Existing deployments are unaffected
  until the flag is flipped.
- When flipping the flag, populate the new env vars per
  `specs/004-voucher-data-minimisation/quickstart.md` § 1–2: at minimum
  `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes) and
  `CASHU_MINT_GRAFANA_RO_PASSWORD`. Boot fails closed when the salt is
  unset or too short.
- First boot after flag flip runs the identity backfill (idempotent,
  paginated). `/actuator/health/readiness` stays DOWN until every
  identity column completes; mint stays out of the load balancer
  rotation during that window.

---

## [0.16.0] - 2026-05-23

### Added

- **Spec 001 — Mint Quote Amount Binding and Webhook Integrity** (NUT-04 /
  NUT-19 / FR-001 through FR-014). All P1 + P2 user stories closed. New
  behaviour is opt-in behind `cashu.mint.jpa.enabled=true` (defaults to
  `false`); existing deployments are unaffected until the flag is flipped.
- New `cashu-mint-jpa` Maven module with three append-only / audit-tracked
  tables in PostgreSQL: `mint_quote` (Envers-audited), `issuance_record`,
  and `webhook_event`. Ships its own Flyway migrations under
  `db/migration/spec001/` and an opt-in Spring autoconfig
  (`MintJpaAutoConfiguration`) that provides DataSource, EntityManagerFactory,
  and TransactionManager beans from `cashu.mint.jpa.datasource.*` properties.
- US1: `MintTask` now binds every NUT-04 issuance to the durable quote
  amount (FR-001), CASes `PAID → ISSUING → ISSUED` (FR-002 / FR-011),
  cross-checks the gateway via `Gateway.getAmount(quoteId)` (FR-010), and
  emits `cashu_mint_amount_mismatch_total{path="mint"}` /
  `cashu_mint_quote_cross_check_failures_total{path="mint"}` counters.
- US2: `QuoteStatusUpdater.record(...)` rewrite — webhook `PENDING → PAID`
  transitions now require matching `(amount, unit, payment_method)` and a
  previously-unseen `(provider, provider_event_id)` (FR-005 / FR-006). Each
  delivery persists exactly one append-only `webhook_event` row with the
  resolved outcome (`accepted` / `amount_mismatch` / `unit_mismatch` /
  `method_mismatch` / `duplicate` / `tamper` / `expired` / `noop` /
  `orphan` / `unsigned_rejected` / `signature_invalid`, FR-008).
  `cashu_mint_webhook_event_total{outcome=...}` counter emitted on every
  classification.
- US3: NUT-19 idempotent replay — a retry against an `ISSUED` quote with
  the same blinded outputs returns the previously signed promises; with
  different outputs, `quote_already_issued`. Bounded-backoff polling
  (50/100/200/400/800 ms) when a concurrent writer is mid-`ISSUING`.
  `cashu_mint_idempotent_replay_total{path="mint"}` counter on every replay.
- FR-007 — `WebhookSecretStartupValidator` fails the Spring context in any
  non-`local` profile when `cashu.mint.webhook.shared-secret` is unset.
  Existing `webhook.secret` continues to work; the canonical key going
  forward is `cashu.mint.webhook.shared-secret`.
- FR-009 — `LongArithmeticArchTest` (ArchUnit) fails the build if any
  amount-bearing field in `cashu-mint-protocol`'s `tasks/` or `ports/`
  packages is typed `int`/`Integer` instead of `long`. Caught and widened
  one existing regression (`MintQuoteTask.amount`).
- New port interfaces in `cashu-mint-protocol/.../proto/ports/`:
  `MintQuote` / `MintQuoteRepository` / `IssuanceRecord` /
  `IssuanceRecordRepository` / `WebhookEvent` / `WebhookEventRepository` /
  `ProviderIdentifier` / `MintIntegrityContext`.
- `OutputsHash` utility — SHA-256 over sorted `(amount, keyset_id, B_)`
  tuples — backs the NUT-19 idempotent-replay equality check.
- New docs: `docs/how-to/configure-webhook-integrity.md` covers the
  mandatory-secret contract, provider key, feature flag, outcome → HTTP
  status table, and operator reconciliation SQL.
- `NutAdvertisementContractTest` pins the advertised NUT set in
  `mint.yaml` against a documented test-backed allow list
  ({4, 5, 7, 8, 9, 10, 11, 12, 17}); the test fails if a NUT is added or
  removed without updating the inventory.
- `MintQuoteJpaRepository` class-level Javadoc ships two operator
  reconciliation queries (FR-001 + FR-005 daily invariants).

### Changed

- Webhook idempotency is now durably keyed by
  `(provider, provider_event_id)` per FR-006.
  `PaymentNotification#getIdempotencyKey()` (the legacy `paymentMethod:quoteId`
  cache key) is `@Deprecated`. The cache-only path is retained as a
  fallback when the durable repositories are absent (unit-test contexts).
- `QuoteStatusUpdater` Caffeine cache is now a read-through accelerator
  only; write decisions go through the durable repositories when wired.
- `WebhookSignatureValidator` removed the silent skip-if-blank branch
  (FR-007). A missing secret now fails validation rather than passing
  through.
- `cashu-mint-admin` modules aligned to 0.16.0 to catch up the stale
  0.14.2 parent reference that lingered after the 0.15.0 bump.

### Fixed

- `MintJpaAutoConfiguration` now wires its own `DataSource`,
  `EntityManagerFactory`, `JpaTransactionManager`, and Flyway runner —
  earlier scaffolding declared `@EnableJpaRepositories` without a
  DataSource bean, so the `cashu.mint.jpa.enabled=true` path was dead
  code that failed at context startup.
- `MintQuoteJpaRepository#casLifecycle` is now `@Transactional` —
  earlier the modifying JPQL CAS threw
  `InvalidDataAccessApiUsageException` when called from non-transactional
  code paths.
- `QuoteStatusUpdater#record` is now `@Transactional` to wrap the
  combined `webhook_event` insert + `mint_quote` CAS.
- Spec 001 Flyway migrations moved to `db/migration/spec001/` to avoid
  V1 collisions with `payment-adapter-model` and `cashu-vault-jpa` on the
  classpath.
- All hash columns (`request_hash`, `outputs_hash`, `signature_digest`)
  switched from `CHAR(64)` to `VARCHAR(64)` so Hibernate's schema validator
  doesn't reject the mapping.
- `flyway-database-postgresql` pinned in `dependencyManagement` to match
  `${flyway.version}` (11.2.0); Spring Boot's transitive resolution was
  pulling 11.7.2 and crashing the Flyway runner.

### Tests

- `cashu-mint-protocol`: 171 → 190 cases (+19) — `OutputsHashTest`,
  `MintTaskAmountValidationTest`, `IssuingConcurrencyTest`,
  `LongArithmeticArchTest`, `NutAdvertisementContractTest`, plus
  extended `MintQuoteTaskTest`.
- `cashu-mint-webhook`: 38 → 49 cases (+11) — `QuoteStatusUpdaterDurableTest`
  exercises the full outcome matrix with mocked durable repositories.
- `cashu-mint-rest-it`: 5 new spec-001 IT classes (18 cases) under
  Testcontainers Postgres 16-alpine — `FlywayMigrationIT`,
  `WebhookSignatureBootIT`, `WebhookAmountBindingIT`,
  `MintQuoteAmountBindingIT`, `MintQuoteConcurrencyIT`.

---

## [0.15.0] - 2026-05-22

### Added

- `/v1/info` now advertises NUT-11 (P2PK spending conditions). The mint
  already verified P2PK secrets at the wire (`P2PKSpendingCondition` wired
  into `VerifyProofsTask:128`) but the info response silently omitted the
  entry, so clients that gate P2PK use on `/v1/info` — including
  `imani-gateway-core`'s spec-029 in-person delivery saga branch — would
  refuse the path despite swap requests working. The new entry is a simple
  `supported: true` (no per-method config), matching NUT-09 / NUT-10 / NUT-12.

---

## [0.14.2] - 2026-05-12

### Fixed

- `CheckStateTask` (NUT-07 `/v1/checkstate`) was passing the already-computed hash-to-curve point `Y` back through `DefaultProofVaultService.retrieveProof()`, which calls `SecretUtil.toYFromString` (= `hash_to_curve`) again. The second hash produced a point that no stored proof was keyed under, so every checkstate response was a false `UNSPENT` regardless of the proof's actual state. Wallets relying on NUT-07 as a pre-spend oracle were flying blind, only learning that proofs were SPENT/PENDING when the subsequent swap rejected them.
- `VoucherSpendingCondition.verify()` rejected proofs with `verify_proof_already_used_error` for *any* existing `ProofEntity`, not just terminal `STATE_SPENT`. This blocked legitimate saga retries when the same proofs were still in `STATE_PENDING` from a prior in-flight attempt. The condition now only rejects on `STATE_SPENT`, mirroring `InvalidateProofsTask.storeAndInvalidateIdempotent()`'s idempotent recovery path.
- `RSSSpendingCondition.verify()` had the same PENDING-treated-as-terminal bug; same fix applied.

### Added

- `ProofVaultService.retrieveProofByY(yHex)` — new lookup method for callers that already hold the hash-to-curve point Y (NUT-07 wire shape). Skips the hash step that `retrieveProof(secret)` applies for raw-secret callers. `CheckStateTask` now uses this method.
- `DefaultProofVaultServiceTest` — regression coverage proving `SecretUtil.toYFromString` is not idempotent on Y, and pinning the contract for both lookup methods.

---

## [0.14.1] - 2026-02-18

### Added

- HashiCorp Vault service and initialization scripts to `docker-compose.dev.yml` and `docker-compose.prod.yml`
- Admin module: user guide tutorial and transactional outbox explanation documentation

### Changed

- Updated `payment-adapter` dependency to 0.10.0
- Updated `cashu-vault` dependency to 0.7.0

### Fixed

- Admin module: made lifecycle history append idempotent for outbox at-least-once redelivery
- Admin module: added missing Flyway migration scripts and made outbox handler fail-fast
- Admin module: corrected test migration paths to `db/migration/admin/`
- Protocol: resolved DLEQ non-determinism and duplicate storage key issues in tests

---

## [0.14.0] - 2026-02-17

### Added

- `CONTRIBUTING.md` contributor guide with branch naming, commit conventions, and PR process
- `docs/reference/glossary.md` — Cashu and ecash terminology (~35 terms)
- `docs/reference/error-codes.md` — complete REST API error code reference with causes and resolutions
- `docs/how-to/deploy-production.md` — production deployment checklist (TLS, secrets, monitoring, backups)
- `docs/how-to/develop-gateway-adapter.md` — guide for implementing custom payment gateway adapters
- `docs/tutorials/websocket-client-example.md` — step-by-step WebSocket client tutorial with wscat and JavaScript
- `docs/how-to/troubleshoot-common-issues.md` — consolidated troubleshooting guide
- `docs/explanations/virtual-thread-adoption.md` — consolidated virtual thread adoption narrative
- Admin module: vault provisioning saga with compensation logic
- Admin module: active mint checks by unit during lifecycle create and resume operations
- Admin module: integration and E2E tests for vault provisioning saga

### Changed

- Expanded `docs/reference/rest-api.md` with WebSocket endpoint documentation and error response section
- Expanded `docs/reference/nuts.md` from link list to table with implementation classes and status
- Added NUT-12 (DLEQ proofs) and NUT-17 (WebSocket subscriptions) to `docs/explanations/architecture-and-nuts.md`
- Merged `voucher-mint-quote-overview.md` TL;DR into `voucher-mint-quote-percentage.md`
- Updated `README.md` with admin module section, contributing link, and Diataxis documentation index
- Updated `TESTING.md` troubleshooting section to cross-reference new troubleshooting guide
- Archived `docs/loom/` files to `docs/archive/loom/` with header notes pointing to consolidated doc
- Admin module: updated Flyway migration location and Jackson serialization settings
- Admin module: enhanced error handling and configuration
- Updated `mint-admin-web` vite config to use environment variable for API proxy target
- Added Byte Buddy dependency and excluded `slf4j-simple` from `cashu-lib-common`
- Converted `cashu-mint-admin` from git submodule to regular directory

### Removed

- Deleted `docs/explanations/voucher-mint-quote-overview.md` (merged into percentage doc)
- Removed `docs/loom/` directory (archived to `docs/archive/loom/`)

---

## [0.13.0] - 2026-02-16

### Added

- Integrated `cashu-mint-admin` as a git submodule with CLI, REST API, web UI, and test modules
- Added `mint-admin-web` and `mint-admin-tests` to the Maven reactor
- New documentation: getting started tutorial, architecture overview, NUT implementation guide, E2E test guide, environment variables reference
- Admin module documentation: CLI command reference, REST API reference, configuration reference, architecture explanation

### Changed

- Enhanced error handling and updated database configuration for PostgreSQL
- Overhauled `docs/` directory — expanded skeleton files, removed misplaced content, consolidated trivial docs
- Updated CLAUDE.md docs file count from 21 to 32

### Removed

- Removed misplaced imani-bridge files (`SECURE_CODING.md`, `security-implementation-plan.md`)
- Removed unfilled `baseline-metrics.md` template
- Consolidated `java-version.md`, `license.md`, and `disclaimer.md` into parent docs

---

## [0.12.2] - 2026-02-03

### Changed

- Updated `cashu-vault` dependency to 0.6.0

### Added

- Added security section to README documenting Oracle Java Secure Coding Guidelines compliance

---

## [0.12.1] - 2026-02-02

### Changed

- **QuoteStatusUpdater**: Migrated from count-based to weight-based eviction using `maximumWeight()` and custom weigher for accurate memory management
- **QuoteStatusUpdater**: Added `recordStats()` and Micrometer metrics integration (`CaffeineCacheMetrics.monitor()`) for cache observability
- **VoucherQuoteRegistry**: Replaced unbounded `ConcurrentHashMap` with Caffeine cache (24h TTL, 10k max entries) to prevent memory leaks

### Improved

- **Collection Capacity Optimization**: Added initial capacity to HashMap/ArrayList constructors across protocol tasks to reduce resizing overhead:
  - `MintProtocolUtil.createLightningAddressRequest()`: HashMap capacity 3
  - `MintTask.execute()`: HashMap capacity 4
  - `SwapTask.execute()`: ArrayList capacity matching input size
  - `RestoreSignaturesTask`: ArrayList capacities for outputs/signatures
  - `P2PKSpendingCondition`: Estimated ArrayList capacity
- **SwapTask**: Optimized double stream iteration to single pass for voucher proof detection

### Fixed

- Removed unused import for `DBMintVault` in `MintProtocolUtil`
- Added `results/` directory to `.gitignore`

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
