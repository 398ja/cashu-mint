# Implementation Plan: Melt Path Burn-First Ordering and Burn-Amount Check

**Branch**: `002-melt-burn-ordering` | **Date**: 2026-05-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-melt-burn-ordering/spec.md`

## Summary

Rewrite the NUT-05 melt path so that (1) the burn-amount check is
expressed directly as `sum(proofs.amount) >= invoiceAmount +
exactFeeReserve` using `long` arithmetic, and (2) input proofs are
durably held (`UNSPENT → PENDING`) in a single transaction
*before* `gateway.pay(quoteId)` is called. The melt saga becomes a
first-class entity with explicit state transitions
(`PROOFS_HELD → PAYMENT_SENT → COMPLETED` happy path, plus
`FAILED`, `PAYMENT_SENT_BURN_FAILED`, `PAYMENT_UNKNOWN`
compensation states). Ambiguous provider responses fail closed —
no automatic retry of the external payment, regardless of
intermediate failures. Saga state + transition timeline are
queryable via an admin-only endpoint for reconciliation. NUT-08
overpaid-melt change return is computed against the persisted saga
record after `COMPLETED`, never against the in-memory request.

Technical approach: introduce a `MeltSaga` JPA entity and an
append-only `MeltSagaTransition` ledger in the
`cashu-mint-jpa` module (created by spec 001); add a
`melt_saga_id` foreign key column to `cashu-vault`'s `proof_entity`
so a `PENDING` proof is exclusively held by exactly one saga; harden
`MeltTask` to express the burn check correctly and to drive the
saga state machine; route `gateway.pay(quoteId)` calls through a
new `LightningPaymentPort` so ambiguous responses can be detected
and reflected in saga state; replace `int` / `Stream.mapToInt(...)`
over amounts with `long`. Operator-visible alerts fire on entry
into `PAYMENT_SENT_BURN_FAILED` and on every transition into
`PAYMENT_UNKNOWN`.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- Spring Boot 3.5.10
- cashu-lib 0.16.0 (`Proof`, `BlindedMessage`, melt request/response
  types)
- payment-adapter 0.10.1 (`Gateway.pay(quoteId)` — needs to surface
  ambiguous outcomes; see research R4 for cross-repo coordination)
- cashu-vault 0.7.0 (`ProofRepository`, `ProofState` enum — needs a
  `melt_saga_id` column; see research R3 for the cross-repo change)
- Hibernate JPA + Hibernate Envers
- Flyway 11.2.0
**Storage**: PostgreSQL 16 (same database as spec 001). New tables
`melt_saga` and `melt_saga_transition`. New nullable column
`melt_saga_id` on `cashu-vault`'s `proof_entity` (cross-repo
migration tracked in cashu-vault spec backlog; see research R3).
**Testing**:
- Unit: JUnit 5 + Mockito for `MeltTask` state machine
- Integration: JUnit 5 + Testcontainers PostgreSQL + a mock
  `LightningPaymentPort` that can be programmed to return
  `success`, `definitive_failure`, or `ambiguous` (research R4)
**Target Platform**: Linux server (Docker), JVM 21.
**Project Type**: Multi-module Maven service (continuation of
spec 001's layout — `cashu-mint-protocol` consumes ports
implemented in `cashu-mint-jpa`).
**Performance Goals**:
- p95 `MeltTask` latency ≤ existing baseline + 15ms (one extra DB
  transaction round-trip for the `PROOFS_HELD` commit)
- The new "wait for definitive provider response" loop must not
  spin; each provider call uses an explicit timeout (research R4)
- Throughput regression ≤ 5% under realistic melt workload
**Constraints**:
- All amount arithmetic MUST use `long` — `int` /
  `Stream.mapToInt(...)` removed from validation paths (FR-009)
- `PROOFS_HELD` commit MUST precede `gateway.pay(...)` invocation
  in every code path (FR-003, SC-003)
- No auto-retry of `gateway.pay` after definitive failure (FR-007)
- `PAYMENT_UNKNOWN` sagas MUST NOT auto-advance to `COMPLETED`
  without independent confirmation (FR-008)
- NUT-08 change return computed only after `COMPLETED` (FR-013)
**Scale/Scope**:
- Today: low-volume staging; design targets up to 50 melts per
  second sustained on a single instance
- `melt_saga_transition` grows linearly; archival is out of scope

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution: cashu-mint v1.1.0.

| Principle | Gate | Status |
|---|---|---|
| I. Token Integrity | No silent deflation — FR-001/002 enforce `sum(proofs) >= invoice + exactFeeReserve` | ✅ Designed |
| I. Token Integrity | Durable financial state — `MeltSaga` + `MeltSagaTransition` in PostgreSQL; `PROOFS_HELD` commits before `gateway.pay` (FR-003, FR-006) | ✅ Designed |
| I. Token Integrity | `long` arithmetic — FR-009; ArchUnit test from spec 001 extended to cover melt path | ✅ Designed |
| I. Token Integrity | Operator-visible alerts — FR-011 fires alerts on `PAYMENT_SENT_BURN_FAILED` and `PAYMENT_UNKNOWN` | ✅ Designed |
| II. Protocol Compliance | NUT-05 / NUT-08 pinned references — FR-013, FR-014 | ✅ Designed |
| III. Clean Architecture | Saga state in `cashu-mint-jpa`; `MeltTask` depends on `MeltSagaRepository` port + `LightningPaymentPort` | ✅ Designed |
| IV. Testing Discipline | Testcontainers PostgreSQL; failure-injection mock for `LightningPaymentPort`; boundary cases (under/exact/over) | ✅ Designed |
| V. Virtual Threads | Provider polling for `PAYMENT_UNKNOWN` reconciliation uses VT executor with explicit timeouts | ✅ Designed |
| VI. Secure Coding & Code Quality | Strict parsing of provider responses; missing `payment_hash` → `PAYMENT_UNKNOWN`; admin auth on saga query endpoint | ✅ Designed |

**Result**: PASS. Complexity Tracking section intentionally empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-melt-burn-ordering/
├── plan.md              # This file
├── research.md          # Phase 0 — provider-response taxonomy, vault FK, etc.
├── data-model.md        # Phase 1 — MeltSaga, MeltSagaTransition, ProofEntity.melt_saga_id
├── quickstart.md        # Phase 1 (deferred)
├── contracts/           # Phase 1 (deferred — port interfaces)
└── tasks.md             # Phase 2 (NOT created here)
```

### Source Code (repository root)

```text
cashu-mint-protocol/
└── src/main/java/xyz/tcheeric/cashu/mint/protocol/
    ├── tasks/
    │   └── MeltTask.java                    # MODIFY: burn check uses long; drive saga; call payment port
    ├── ports/                                # NEW (or extended from spec 001)
    │   ├── MeltSagaRepository.java          # NEW
    │   └── LightningPaymentPort.java        # NEW — wraps Gateway.pay to surface ambiguous outcomes
    └── domain/
        ├── MeltSagaState.java               # NEW enum
        └── PaymentOutcome.java              # NEW sealed type: Success | DefinitiveFailure | Unknown

cashu-mint-jpa/                              # FROM SPEC 001
├── src/main/java/xyz/tcheeric/cashu/mint/jpa/
│   ├── entity/
│   │   ├── MeltSagaEntity.java              # NEW
│   │   └── MeltSagaTransitionEntity.java    # NEW (append-only)
│   ├── repository/
│   │   ├── MeltSagaJpaRepository.java       # NEW — implements MeltSagaRepository
│   │   └── MeltSagaTransitionJpaRepository.java
│   └── adapter/
│       └── PaymentAdapterLightningPort.java # NEW — implements LightningPaymentPort over payment-adapter
└── src/main/resources/db/migration/
    ├── V20260523_001__create_melt_saga.sql
    └── V20260523_002__create_melt_saga_transition.sql

# Cross-repo dependency (cashu-vault):
# cashu-vault-jpa/src/main/resources/db/migration/
#   Vyyyymmdd__add_melt_saga_id_to_proof.sql  (nullable column + index)
# See research R3.

cashu-mint-rest-it/
└── src/test/java/xyz/tcheeric/cashu/mint/it/
    ├── MeltBurnAmountIT.java                # SC-001: under/exact/over
    ├── MeltBurnFirstOrderingIT.java         # SC-003: PROOFS_HELD precedes PAYMENT_SENT
    ├── MeltPaymentFailureIT.java            # FR-007: no auto-retry after definitive failure
    ├── MeltPaymentUnknownIT.java            # FR-008: no advance to COMPLETED without confirmation
    └── MeltPaymentSentBurnFailedIT.java     # FR-006: proofs stay PENDING; operator alert
```

**Structure Decision**:

Continue the spec-001 split: `cashu-mint-protocol` holds tasks +
ports; `cashu-mint-jpa` holds entities + repositories +
Flyway migrations + payment-adapter adapter. The new
`LightningPaymentPort` is a thin wrapper around payment-adapter's
`Gateway.pay(quoteId)` whose responsibility is to (a) parse the
provider response strictly, (b) emit a typed `PaymentOutcome`, and
(c) enforce the explicit timeout from research R4. The adapter
implementation in `cashu-mint-jpa` (or a sibling
`cashu-mint-adapter` module if growth justifies it later) consults
payment-adapter; the protocol module sees only the typed outcome.

This keeps `MeltTask` free of provider-response parsing logic and
makes the failure-injection test in
`MeltPaymentSentBurnFailedIT` straightforward to write.

The cashu-vault side change — adding a nullable `melt_saga_id`
column to `proof_entity` — is **out of scope** for this branch but
is a prerequisite. It is tracked separately in cashu-vault's spec
backlog (research R3); this spec assumes lock-step delivery.

## Complexity Tracking

> Constitution Check passed. Section intentionally empty.
