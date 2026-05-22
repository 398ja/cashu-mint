# Implementation Plan: Mint Quote Amount Binding and Webhook Integrity

**Branch**: `001-mint-quote-webhook-integrity` | **Date**: 2026-05-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-mint-quote-webhook-integrity/spec.md`

## Summary

Bind every NUT-04 mint issuance to a durable, amount-matched, single-use
quote record. Bind every webhook `PENDING → PAID` transition to an
exact amount + unit + payment-method + provider-event-id match. The
quote's lifecycle (`UNPAID → PENDING → PAID → ISSUING → ISSUED`) moves
through compare-and-set transitions in PostgreSQL; the issuance record
is append-only and keyed by `quote_id`; webhook events are append-only
and keyed by `(provider, provider_event_id)`. Idempotent retry
(NUT-19 cached responses) returns the previously signed promises for
the same outputs; mismatched outputs against an already-issued quote
are rejected. Webhook signature validation becomes mandatory in
non-local profiles, with startup failure if the shared secret is
unset.

Technical approach: introduce a `MintQuote` JPA entity in
`cashu-mint-protocol`'s persistence module (or a new `cashu-mint-jpa`
sibling, TBD in research) with a Flyway migration; add
`IssuanceRecord` (append-only) and `WebhookEvent` (append-only)
entities; harden `MintTask` to enforce the amount equality and
state-machine transitions inside one transaction; harden
`QuoteStatusUpdater` / `PaymentWebhookController` to enforce the
amount-bound, signed, event-id-keyed contract; remove the
`paymentMethod:quoteId` idempotency key in favour of
`(provider, provider_event_id)`; replace any `int` /
`Stream.mapToInt(...)` over amounts with `long`. Hibernate Envers
auto-audits `MintQuote.lifecycle_state` transitions. Existing
in-process caches (`QuoteStatusService`) become read-through
accelerators only.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- Spring Boot 3.5.10 (via `spring-boot-dependencies` BOM)
- cashu-lib 0.16.0 (BDHKE primitives, `BlindedMessage`, `BlindSignature`)
- payment-adapter 0.10.1 (`Gateway`, `Gateway.checkPaymentStatus`,
  `Gateway.getAmount`)
- nostr-java 1.3.0 (signed event types for downstream consumers; not
  used in this feature's hot path)
- BouncyCastle 1.81 (`bcprov-jdk18on`) — existing; no new crypto added
- Hibernate JPA + Hibernate Envers (consumed via cashu-vault 0.7.0)
- Flyway 11.2.0 — existing; new migrations land in the JPA module
  hosting `MintQuote`
**Storage**: PostgreSQL 16 (existing `cashu-mint` instance; same
database that backs the existing webhook tables in `cashu-mint-webhook`).
New tables `mint_quote`, `issuance_record`, `webhook_event` plus
matching Envers `_AUD` tables.
**Testing**:
- Unit: JUnit 5.12.2 via `mvn -q test`
- Integration: JUnit 5 + Testcontainers PostgreSQL 1.20.4
  in `cashu-mint-rest-it` (run via `-P integration-tests`); H2 is
  rejected for the write path per Constitution IV
**Target Platform**: Linux server (Docker) — same runtime as the
existing cashu-mint REST service. JVM 21, Spring Boot embedded
Tomcat.
**Project Type**: Multi-module Maven service (existing layout).
This feature lands across:
- `cashu-mint-protocol` (task hardening + ports)
- `cashu-mint-webhook` (signature validation + event persistence)
- a JPA-hosting module for the new entities (see Structure Decision)
- `cashu-mint-rest-it` (integration tests)
**Performance Goals**:
- p95 `MintTask` latency ≤ existing baseline + 10ms (the new
  state-machine transitions add one transaction round-trip)
- p95 webhook handler latency ≤ 100ms (signature verify + persist
  event + CAS state transition)
- Throughput regression ≤ 5% under realistic mint workload
**Constraints**:
- All financial amounts MUST be `long`; no `int` /
  `Stream.mapToInt(...)` allowed in validation paths
  (Constitution I, FR-009)
- All state transitions MUST be atomic and durable per FR-002, FR-005
- No in-process cache may shadow the durable lifecycle state
- Webhook signature secret MUST be present at startup in
  `staging`/`prod` profiles (FR-007, SC-005)
- Idempotent NUT-19 replay MUST return identical signatures (FR-003)
**Scale/Scope**:
- Today: low-volume staging; design targets up to 100 mint requests
  per second sustained on a single instance
- Quote table grows by `mint_quote` rows; `issuance_record` and
  `webhook_event` are append-only — retention/archival policy is
  out of scope here

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution: cashu-mint v1.1.0
(`.specify/memory/constitution.md`).

| Principle | Gate | Status |
|---|---|---|
| I. Token Integrity (NON-NEGOTIABLE) | No silent inflation — FR-001/002/003 enforce `sum(outputs) == quote.amount` + single-use + idempotent replay | ✅ Designed |
| I. Token Integrity | No silent deflation — out of scope here (melt path = spec 002) | ✅ N/A |
| I. Token Integrity | Durable financial state — `MintQuote`, `IssuanceRecord`, `WebhookEvent` all in PostgreSQL (FR-004, FR-008, FR-011) | ✅ Designed |
| I. Token Integrity | Amount-bound webhooks — FR-005, FR-006 enforce match on amount/unit/method/event-id | ✅ Designed |
| I. Token Integrity | `long` arithmetic — FR-009 + Code-quality gate in CI | ✅ Designed |
| I. Token Integrity | Operator-visible alerts — FR-005/010/012 emit structured logs + alerts on mismatch & cross-check failure | ✅ Designed |
| II. Protocol Compliance (Cashu NUTs) | NUT-04 / NUT-06 / NUT-19 / NUT-20 pinned references — FR-013, FR-014 | ✅ Designed |
| III. Clean Architecture | Persistence behind a port in `cashu-mint-protocol`; controllers stay thin | ✅ Designed |
| IV. Testing Discipline | Testcontainers PostgreSQL; realistic under-/over-/exact-mint scenarios mandatory | ✅ Designed |
| V. Virtual Threads | I/O across the gateway + vault uses existing VT executors | ✅ Inherited |
| VI. Secure Coding & Code Quality | Mandatory webhook signatures, startup-fail when unset (FR-007); structured security logging (FR-008) | ✅ Designed |

**Result**: PASS — no constitution violations require justification.
The Complexity Tracking section below is intentionally empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-mint-quote-webhook-integrity/
├── plan.md              # This file
├── research.md          # Phase 0 output — open questions resolved
├── data-model.md        # Phase 1 output — MintQuote, IssuanceRecord, WebhookEvent
├── quickstart.md        # Phase 1 output — local-dev smoke flow (deferred)
├── contracts/           # Phase 1 output — Java port interfaces (deferred)
└── tasks.md             # Phase 2 output (NOT created here; via /speckit.tasks)
```

### Source Code (repository root)

```text
cashu-mint-protocol/                 # Existing — protocol tasks + ports
├── src/main/java/xyz/tcheeric/cashu/mint/protocol/
│   ├── tasks/
│   │   ├── MintQuoteTask.java          # MODIFY: persist quote via MintQuoteRepository
│   │   ├── MintTask.java               # MODIFY: enforce sum(outputs)==quote.amount; CAS to ISSUED; emit IssuanceRecord
│   │   └── ...
│   └── ports/                          # NEW: MintQuoteRepository, IssuanceRecordRepository
└── pom.xml

cashu-mint-webhook/                  # Existing — receives provider notifications
├── src/main/java/xyz/tcheeric/cashu/mint/webhook/
│   ├── PaymentWebhookController.java   # MODIFY: enforce signature; persist WebhookEvent; CAS PENDING->PAID
│   ├── QuoteStatusUpdater.java         # MODIFY: amount/unit/method match + (provider,event_id) idempotency
│   ├── QuoteStatusService.java         # MODIFY: become read-through cache only
│   └── WebhookSignatureValidator.java  # MODIFY: fail-on-missing-secret in non-local profiles
└── pom.xml

cashu-mint-jpa/                      # NEW MODULE (Structure Decision — see below)
├── src/main/java/xyz/tcheeric/cashu/mint/jpa/
│   ├── entity/
│   │   ├── MintQuoteEntity.java
│   │   ├── IssuanceRecordEntity.java
│   │   └── WebhookEventEntity.java
│   ├── repository/
│   │   ├── MintQuoteJpaRepository.java   # implements MintQuoteRepository port
│   │   ├── IssuanceRecordJpaRepository.java
│   │   └── WebhookEventJpaRepository.java
│   └── EnversConfig.java
├── src/main/resources/db/migration/
│   ├── V20260522_001__create_mint_quote.sql
│   ├── V20260522_002__create_issuance_record.sql
│   └── V20260522_003__create_webhook_event.sql
└── pom.xml

cashu-mint-rest-it/                  # Existing — integration tests
└── src/test/java/xyz/tcheeric/cashu/mint/it/
    ├── MintQuoteAmountBindingIT.java   # NEW: SC-001 / FR-001 / FR-002 / FR-003
    ├── MintQuoteConcurrencyIT.java     # NEW: SC-002
    ├── WebhookAmountBindingIT.java     # NEW: FR-005 / FR-006
    └── WebhookSignatureBootIT.java     # NEW: SC-005 (startup fails when secret unset)
```

**Structure Decision**:

Two options were considered for hosting the new JPA entities and
Flyway migrations:

- **Option A (chosen)**: introduce a new `cashu-mint-jpa` module.
  Keeps `cashu-mint-protocol` free of JPA/Hibernate (Constitution III
  Clean Architecture); the protocol module depends on a port
  interface (`MintQuoteRepository`) implemented in the JPA module.
- **Option B (rejected)**: add JPA directly to `cashu-mint-protocol`.
  Faster to land but blurs the architectural boundary — `MintTask`
  would couple to JPA. Not worth the long-term cost.

The chosen layout mirrors the cashu-vault split (`cashu-vault-jpa`
+ `cashu-vault-api`) and keeps protocol tasks pure. Spring Boot
auto-configuration wires the JPA repository implementations into
the port interfaces at runtime; the REST module is unchanged.

Flyway migrations land in the new module and are picked up by the
existing Flyway runner; baseline + ordering are validated by the
existing Flyway strict validation in CI.

## Complexity Tracking

> Constitution Check passed. Section intentionally empty.
