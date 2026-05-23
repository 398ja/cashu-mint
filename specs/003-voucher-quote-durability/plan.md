# Implementation Plan: Voucher Quote Durability and Funding-Source Binding

**Branch**: `003-voucher-quote-durability` | **Date**: 2026-05-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-voucher-quote-durability/spec.md`

## Summary

Move voucher quote classification, face value, charged amount,
merchant/customer identity, funding source, and lifecycle state out
of the in-memory `VoucherQuoteRegistry` and into a durable
`VoucherQuote` JPA entity. Require every voucher proof issuance to
be backed by exactly one durable `VoucherFunding` record — a
settled customer payment, a merchant-funded debit, or an explicit
merchant IOU (subject to per-profile policy). Make the funding
lookup → issuance link auditable via an append-only
`VoucherIssuance` ledger so the operator can answer
"given voucher proof X, where did the funding come from?" in one
indexed hop. Voucher support stays out of the NUT-06 `nuts` key
(per Constitution II — vouchers are a vendor extension). Voucher
endpoints require authentication, are rate-limited per principal,
and idempotent on `idempotency_key`.

Technical approach: extend the spec-001 `MintQuote` durable record
with a voucher-quote variant (joined-subclass strategy, or a
separate sibling table — see Structure Decision); introduce
`VoucherFunding` as a polymorphic durable record with three
variants (`CustomerPaymentFunding`, `MerchantDebitFunding`,
`MerchantIouFunding`); introduce `VoucherIssuance` as an
append-only join row linking voucher quote → funding →
spec-001 `IssuanceRecord`. Harden `VoucherMintQuoteTask` to
persist voucher quotes durably; harden the voucher branch of
`MintTask` to require a matching `VoucherFunding` row (selected by
policy) before issuance; relegate `VoucherQuoteRegistry` to a
read-through cache. Add auth middleware on voucher endpoints,
per-principal rate limits, and idempotency-key handling. Operator
alerts on `merchant_iou` issuance and on funding-record orphans.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- Spring Boot 3.5.10
- cashu-lib 0.16.0 (voucher types where they exist; protocol code
  for blinded-output validation)
- cashu-voucher 0.6.1 (the voucher-specific protocol library)
- payment-adapter 0.10.1 (`Gateway.getAmount`, webhook events that
  fund `CustomerPaymentFunding`)
- spec-001 `MintQuote` durable model (the voucher quote extends or
  parallels this — see Structure Decision)
- Hibernate JPA + Hibernate Envers
- Flyway 11.2.0
- Spring Security (for auth on voucher endpoints — already in use
  in cashu-mint-admin-rest; needs to be applied to voucher routes
  as well)
**Storage**: PostgreSQL 16. New tables `voucher_quote`,
`voucher_funding` (+ child tables for variants), `voucher_issuance`,
plus matching Envers `_AUD` tables. Continues in the
`cashu-mint-jpa` module introduced by spec 001.
**Testing**:
- Unit: JUnit 5 for `VoucherMintQuoteTask`, policy evaluator, and
  the funding-resolver
- Integration: JUnit 5 + Testcontainers PostgreSQL. Mandatory
  restart test for voucher quote classification and face value
  survival (SC-002).
**Target Platform**: Linux server (Docker), JVM 21.
**Project Type**: Multi-module Maven service (continues spec-001's
layout).
**Performance Goals**:
- p95 voucher-quote-creation latency ≤ existing baseline + 20ms
  (one extra DB transaction for the durable record)
- p95 voucher-mint latency ≤ existing baseline + 15ms
- Throughput regression ≤ 5%
**Constraints**:
- No voucher proof issued without a `VoucherFunding` row (FR-002,
  SC-001)
- No in-memory cache shadows write decisions (FR-003)
- All voucher amounts `long` (FR-011)
- Voucher not advertised under NUT-06 `nuts` key (FR-010, SC-004)
- Auth required on every voucher endpoint (FR-007, SC-003)
- Idempotent on `idempotency_key` (FR-009)
**Scale/Scope**:
- Today: low-volume staging; design targets up to 10 voucher
  quotes per second sustained (vouchers are not a hot path
  vs. regular mint).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution: cashu-mint v1.1.0.

| Principle | Gate | Status |
|---|---|---|
| I. Token Integrity | No silent inflation — FR-002 requires durable funding before issuance | ✅ Designed |
| I. Token Integrity | Durable financial state — `VoucherQuote`, `VoucherFunding`, `VoucherIssuance` all in PostgreSQL (FR-001, FR-004) | ✅ Designed |
| I. Token Integrity | `long` arithmetic — FR-011; ArchUnit gate extended | ✅ Designed |
| I. Token Integrity | Operator-visible alerts — FR-014 fires alerts on IOU issuance + funding orphan + rate-limit breach | ✅ Designed |
| II. Protocol Compliance | Voucher is non-standard extension on top of NUT-04; FR-010 keeps it out of NUT-06 `nuts` key; FR-013 mandates Javadoc note | ✅ Designed |
| III. Clean Architecture | Voucher entities in `cashu-mint-jpa`; protocol module sees only `VoucherQuoteRepository` + `VoucherFundingResolver` ports | ✅ Designed |
| IV. Testing Discipline | Testcontainers; restart test mandatory (SC-002); per-funding-source happy + sad paths | ✅ Designed |
| V. Virtual Threads | Voucher endpoints fan out across VTs same as the rest of the REST tier | ✅ Inherited |
| VI. Secure Coding & Code Quality | Auth on voucher endpoints (FR-007); rate limit (FR-008); idempotency (FR-009); structured security logs (FR-014) | ✅ Designed |

**Result**: PASS. Complexity Tracking intentionally empty.

## Project Structure

### Documentation (this feature)

```text
specs/003-voucher-quote-durability/
├── plan.md              # This file
├── research.md          # Phase 0 — joined-subclass vs sibling table, IOU policy, etc.
├── data-model.md        # Phase 1 — VoucherQuote, VoucherFunding (3 variants), VoucherIssuance
├── quickstart.md        # Phase 1 (deferred)
├── contracts/           # Phase 1 (deferred — VoucherQuoteRepository, VoucherFundingResolver)
└── tasks.md             # Phase 2 (NOT created here)
```

### Source Code (repository root)

```text
cashu-mint-protocol/
└── src/main/java/xyz/tcheeric/cashu/mint/protocol/
    ├── tasks/
    │   ├── VoucherMintQuoteTask.java          # MODIFY: persist voucher quote durably; record funding intent
    │   └── MintTask.java                       # MODIFY: voucher branch requires VoucherFunding row
    ├── ports/
    │   ├── VoucherQuoteRepository.java         # NEW
    │   ├── VoucherFundingResolver.java         # NEW — encapsulates policy: settled payment / debit / IOU
    │   └── VoucherIssuanceRepository.java      # NEW
    └── domain/
        ├── VoucherQuoteType.java               # existing enum, extended
        └── VoucherFundingSource.java           # NEW enum: CUSTOMER_PAYMENT, MERCHANT_DEBIT, MERCHANT_IOU

cashu-mint-jpa/                                 # FROM SPEC 001
├── src/main/java/xyz/tcheeric/cashu/mint/jpa/
│   ├── entity/
│   │   ├── VoucherQuoteEntity.java             # NEW (extends MintQuoteEntity or sibling — see Structure Decision)
│   │   ├── VoucherFundingEntity.java           # NEW (abstract or @Inheritance JOINED)
│   │   │   ├── CustomerPaymentFundingEntity.java
│   │   │   ├── MerchantDebitFundingEntity.java
│   │   │   └── MerchantIouFundingEntity.java
│   │   └── VoucherIssuanceEntity.java          # NEW (append-only)
│   ├── repository/
│   │   ├── VoucherQuoteJpaRepository.java
│   │   ├── VoucherFundingJpaRepository.java
│   │   └── VoucherIssuanceJpaRepository.java
│   └── service/
│       └── VoucherFundingResolverImpl.java     # NEW — implements VoucherFundingResolver port; reads profile policy
└── src/main/resources/db/migration/
    ├── V20260524_001__create_voucher_quote.sql
    ├── V20260524_002__create_voucher_funding.sql
    ├── V20260524_003__create_voucher_issuance.sql
    └── V20260524_004__migrate_in_memory_vouchers.sql  # one-shot migration helper (if any in-flight vouchers exist)

cashu-mint-rest/                                # voucher REST endpoints
└── src/main/java/xyz/tcheeric/cashu/mint/rest/
    ├── controller/
    │   └── VoucherController.java              # MODIFY: auth, rate limit, idempotency-key middleware
    ├── security/
    │   └── VoucherEndpointSecurityConfig.java  # NEW — Spring Security config for voucher routes
    └── ratelimit/
        └── VoucherRateLimitFilter.java          # NEW — per-principal token bucket (Caffeine-backed)

cashu-mint-webhook/                             # FROM SPEC 001
└── src/main/java/xyz/tcheeric/cashu/mint/webhook/
    └── QuoteStatusUpdater.java                 # MODIFY: when webhook lands a CustomerPaymentFunding, insert the funding row

cashu-mint-rest-it/
└── src/test/java/xyz/tcheeric/cashu/mint/it/
    ├── VoucherQuoteDurableIT.java              # SC-001: every issued voucher traces to a funding row
    ├── VoucherQuoteRestartIT.java              # SC-002: classification + face value survive restart
    ├── VoucherEndpointAuthIT.java              # SC-003: 401/403 before any state mutation
    ├── VoucherNutAdvertisementIT.java          # SC-004: NUT-06 nuts key does NOT include vouchers
    └── VoucherFundingPolicyIT.java             # FR-006: merchant_iou rejected when policy disallows
```

**Structure Decision**:

Option A (chosen — **separate sibling table**): `voucher_quote`
is a new table that **does not** extend `mint_quote`. It has its
own primary key (`quote_id` overlap with `mint_quote.quote_id` is
forbidden by an application-level invariant; documented and
asserted in tests but not by a database FK).

Option B (rejected — joined-subclass inheritance): map
`VoucherQuoteEntity` as a JPA subclass of `MintQuoteEntity` via
`@Inheritance(strategy = JOINED)`. Cleaner OO model.

**Why A over B**: vouchers and regular NUT-04 quotes diverge on
too many fields (face_value, merchant_id, funding_source). Forcing
them through one inheritance hierarchy adds NULL columns to
`mint_quote` for fields that only ever apply to vouchers. The
sibling-table approach keeps `mint_quote` clean (specs 001 and
003 don't have to coordinate column additions every release).
Cost: the FR-005 audit query ("given proof X, return its funding
source") joins through `issuance_record → voucher_issuance →
voucher_funding`; the extra join is cheap with the right indexes.

`VoucherFunding` uses `@Inheritance(strategy = JOINED)` with three
concrete subclasses — that hierarchy stays inside the voucher
domain so it doesn't pollute `mint_quote`.

**UPDATE 2026-05-23**: `imani-bridge` is retired; the successor
voucher purchase orchestration lives in `imani-gateway-atomic`
(`AtomicPurchaseController`), driven from the `imani-apps`
voucher front-end (`voucher/buy.html` via `@imani/atomic-purchase`).
The original text below is preserved for historical context.

The cross-repo coordination with imani-bridge (gateway-side
`WalletPluginAdapter` orchestration) is **out of scope** for this
branch but is a prerequisite. It is tracked separately; this spec
assumes lock-step delivery.

## Complexity Tracking

> Constitution Check passed. Section intentionally empty.
