---

description: "Task list for spec 002 — Melt Path Burn-First Ordering and Burn-Amount Check"
---

# Tasks: Melt Path Burn-First Ordering and Burn-Amount Check

**Input**: Design documents from `/specs/002-melt-burn-ordering/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅. **Inherits the `cashu-mint-jpa` module from spec 001** — spec 001 foundational tasks (T001–T050) MUST be merged before this spec starts.

**Tests**: Integration tests are MANDATORY per spec.md SC-001 through SC-006 and Constitution IV. Failure-injection tests against `LightningPaymentPort` are non-negotiable for `PAYMENT_UNKNOWN` / `PAYMENT_SENT_BURN_FAILED` coverage.

**Organization**: Tasks grouped by user story (US1, US2, US3). Phase 2 foundational covers the saga JPA + the new `LightningPaymentPort` abstraction. US1 is the burn-amount check; US2 is the burn-first ordering + saga state machine; US3 is the operator-visible saga query endpoint.

## Format: `[ID] [P?] [Story] Description`

- **[P]** — parallel-safe.
- **[Story]** — `US1` (under-funded reject) / `US2` (burn-first + saga) / `US3` (saga observability) / `F` (foundational) / `S` (setup) / `X` (polish).

## Path Conventions

- Same as spec 001 — Maven modules at repo root.
- `cashu-mint-jpa/` is **shared with spec 001**; this spec adds entities + migrations into the same module.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify spec 001's foundations are in place; add a payment-mock harness usable by failure-injection tests.

- [X] **T001** [S] Confirm spec 001's `cashu-mint-jpa` module is on the trunk; abort this spec's branch if not. (Procedural check; no code change.)
- [X] **T002** [P] [S] Add `payment-adapter-test` dependency (test scope) to `cashu-mint-rest-it` if not already present; if missing from upstream, introduce a local `MockLightningPaymentPort` test double in `cashu-mint-rest-it/src/test/java/.../it/support/MockLightningPaymentPort.java` that can be programmed to return `Success`, `DefinitiveFailure`, or `Unknown` per call.
- [X] **T003** [P] [S] Wire the `MockLightningPaymentPort` test double via `@TestConfiguration` so integration tests can swap in deterministic outcomes.
- [ ] **T004** [P] [S] Extend the existing `LongArithmeticArchTest` (spec 001 T050) package scope to cover `cashu-mint-protocol/src/main/java/.../tasks/MeltTask` and `domain/MeltSaga*` (Constitution I).

---

## Phase 2: Foundational (Blocking Prerequisites)

### 2A. Cross-repo prerequisite (cashu-vault)

- [ ] **T010** [F] Coordinate with the cashu-vault spec backlog (research R3) the `melt_saga_id` nullable column + partial unique index on `proof_entity`. The cashu-vault PR MUST land first; this spec cannot complete US2 without it. (Add the cashu-vault PR link to this spec's PR description.)
- [ ] **T011** [P] [F] Add `ProofRepository.markPending(proofIds, meltSagaId)` and `clearMeltSaga(proofIds)` helpers on the mint-side `cashu-mint-protocol` proof port (consumed via the existing vault REST adapter); these wrap the cashu-vault calls that set/clear `melt_saga_id`.

### 2B. Database schema (mint side)

- [X] **T020** [F] Create `cashu-mint-jpa/src/main/resources/db/migration/V20260523_001__create_melt_saga.sql` per `data-model.md` § MeltSaga: PK `melt_saga_id`, UNIQUE on `quote_id`, indexes on `current_state` / `created_at` / `provider_event_id`, columns sized per data-model. CHECK constraint on `current_state` enumerating valid states. JSONB columns for `melt_response_cache` and `change_signatures_json`.
- [X] **T021** [F] Create `V20260523_002__create_melt_saga_transition.sql` per `data-model.md` § MeltSagaTransition: composite PK `(melt_saga_id, seq)`, FK to `melt_saga`, indexes.
- [X] **T022** [P] [F] Configure Hibernate Envers to track `MeltSagaEntity.currentState` / `paymentHash` / `providerEventId` (data-model § Envers note); add `melt_saga_aud` migration if Envers needs it explicitly (Envers usually autogenerates schema; verify against project policy).

### 2C. Domain types

- [X] **T030** [F] Create `cashu-mint-protocol/src/main/java/.../protocol/domain/MeltSagaState.java` enum: `PROOFS_HELD, PAYMENT_SENT, COMPLETED, FAILED, PAYMENT_SENT_BURN_FAILED, PAYMENT_UNKNOWN`.
- [X] **T031** [P] [F] Create sealed type `PaymentOutcome` in `cashu-mint-protocol/src/main/java/.../protocol/domain/PaymentOutcome.java` per research R4:
  ```java
  sealed interface PaymentOutcome permits Success, DefinitiveFailure, Unknown {
    record Success(String paymentHash, long amountSettled, long feePaid, String providerEventId) implements PaymentOutcome {}
    record DefinitiveFailure(String reason, String providerCode) implements PaymentOutcome {}
    record Unknown(String reason) implements PaymentOutcome {}
  }
  ```
- [X] **T032** [P] [F] Create `LightningPaymentPort` interface in `cashu-mint-protocol/src/main/java/.../protocol/ports/LightningPaymentPort.java` with `PaymentOutcome pay(String quoteId, Duration timeout)` per research R4.

### 2D. JPA entities & adapters

- [X] **T040** [F] Implement `MeltSagaEntity` in `cashu-mint-jpa/src/main/java/.../jpa/entity/MeltSagaEntity.java`: `@Entity`, `@Audited`, enum mapped as string, `@JdbcTypeCode(SqlTypes.JSON)` for JSONB. All amount fields `long`.
- [X] **T041** [P] [F] Implement `MeltSagaTransitionEntity`: composite ID via `@IdClass`.
- [X] **T042** [P] [F] Implement `MeltSagaJpaRepository` + `MeltSagaTransitionJpaRepository` with the same CAS-update idiom as spec 001:
  ```java
  @Modifying
  @Query("UPDATE MeltSagaEntity s SET s.currentState = :to, s.updatedAt = CURRENT_TIMESTAMP " +
         "WHERE s.meltSagaId = :id AND s.currentState = :from")
  int casState(@Param("id") String id, @Param("from") MeltSagaState from, @Param("to") MeltSagaState to);
  ```
- [X] **T043** [F] Implement `PaymentAdapterLightningPort` in `cashu-mint-jpa/src/main/java/.../jpa/adapter/PaymentAdapterLightningPort.java` per research R4: calls `Gateway.pay(quoteId)` with timeout, parses response strictly, returns `PaymentOutcome`. Missing `payment_hash`, missing status, timeout, or 5xx ⇒ `Unknown`. 4xx with definitive provider code ⇒ `DefinitiveFailure`. Clean success ⇒ `Success`.

### 2E. Port interfaces

- [X] **T050** [F] `MeltSagaRepository` port in `cashu-mint-protocol/src/main/java/.../protocol/ports/MeltSagaRepository.java`: `save`, `findById`, `casState`, `recordTransition`.
- [X] **T051** [P] [F] Wire JPA implementations to ports via `JpaConfig` (extend spec 001's config).

### 2F. Scheduled reconciliation skeleton

- [X] **T060** [P] [F] Create `MeltSagaReconciler` Spring `@Component` with `@Scheduled(fixedDelayString = "${cashu.mint.melt.reconcile-interval:PT60S}")`. Empty skeleton — wired into US2 implementation but kept in foundational so US2 tests can rely on its presence.

**Checkpoint**: All entities, ports, types, and the scheduler skeleton in place. US1, US2, US3 may proceed.

---

## Phase 3: User Story 1 — Under-funded Melt Rejected Before External Payment (Priority: P1) 🎯 MVP

**Goal**: Mint rejects any melt where `sum(proofs.amount) < invoiceAmount + exactFeeReserve`. No external payment is initiated.

**Independent Test**: `MeltBurnAmountIT` exercises boundary cases — proof sum at `invoiceAmount + exactFeeReserve - 1` (fail), `+0` (pass into US2 territory), `+overpaid` (pass into US2 territory). The reject path never invokes the gateway.

### Tests for User Story 1

- [ ] **T100** [P] [US1] `MeltBurnAmountIT`: under/exact/over boundary cases. Verify `gateway.pay` is never called for the under case (Mockito verify on the `LightningPaymentPort` test double).
- [X] **T101** [P] [US1] Unit test `BurnAmountValidatorTest`: pure logic — `sum >= invoice + exactFeeReserve`, all amounts `long`, no overflow on `Long.MAX_VALUE - 1` + `1`.
- [X] **T102** [P] [US1] Unit test `ExactFeeReserveResolverTest`: research R1's mint-computed reserve plus the cross-check against the caller-asserted value. Cross-check mismatch in `staging`/`prod` ⇒ rejection.

### Implementation for User Story 1

- [X] **T110** [US1] Create `BurnAmountValidator` in `cashu-mint-protocol/src/main/java/.../protocol/tasks/BurnAmountValidator.java` exposing
  ```java
  static void requireFunded(long proofSum, long invoiceAmount, long exactFeeReserve);
  ```
  Throws typed `InsufficientInputException` on `proofSum < invoiceAmount + exactFeeReserve`.
- [X] **T111** [US1] Create `ExactFeeReserveResolver` that consults `Gateway.estimateFee(invoice)` (mint-computed per research R1) and cross-checks against `postMeltRequest.getFees()` in `staging`/`prod`. Persists both to the saga (`exact_fee_reserve`, `asserted_fee_reserve`).
- [X] **T112** [US1] Modify `MeltTask.java` (`cashu-mint-protocol/src/main/java/.../protocol/tasks/MeltTask.java`) entry path to call `BurnAmountValidator.requireFunded(...)` BEFORE any other state mutation. Remove the existing `totalAmount` arithmetic — replace with `sum(proofs.amount)` using `LongStream.mapToLong(Proof::getAmount).sum()` (FR-001, FR-009).
- [X] **T113** [US1] Wire the typed `InsufficientInputException` to a 400 `insufficient_input` REST response in `cashu-mint-rest`'s exception handler.
- [X] **T114** [US1] Add Micrometer counter `cashu_mint_melt_insufficient_input_total` (FR-012).

**Checkpoint**: US1 is testable in isolation. The under-funded reject path never reaches `gateway.pay`.

---

## Phase 4: User Story 2 — Proofs Burned Durably Before External Payment (Priority: P1)

**Goal**: Well-funded melt requests transition proofs `UNSPENT → PENDING` in a durable transaction BEFORE `gateway.pay`; ambiguous responses fail closed; explicit compensation states for every failure mode.

**Independent Test**: `MeltBurnFirstOrderingIT` asserts `PROOFS_HELD` commit precedes `PAYMENT_SENT` via `MeltSagaTransition` timeline. `MeltPaymentSentBurnFailedIT` injects an invalidation failure after `gateway.pay` succeeds and asserts the saga lands in `PAYMENT_SENT_BURN_FAILED` with operator alert + proofs held in `PENDING`. `MeltPaymentUnknownIT` returns `Unknown` from the payment port and asserts the saga enters `PAYMENT_UNKNOWN` and the scheduled reconciler polls bounded times before alerting.

### Tests for User Story 2

- [ ] **T200** [P] [US2] `MeltBurnFirstOrderingIT`: happy path. Assert `MeltSagaTransition.seq=1` is `null → PROOFS_HELD`, `seq=2` is `PROOFS_HELD → PAYMENT_SENT`. Assert `MockLightningPaymentPort.pay` is invoked only AFTER the `PROOFS_HELD` row exists (test double verifies via timestamp ordering + DB poll).
- [ ] **T201** [P] [US2] `MeltPaymentFailureIT`: `MockLightningPaymentPort` returns `DefinitiveFailure`. Assert saga ⇒ `FAILED`, proofs returned to `UNSPENT`, `melt_saga_id` cleared on proof rows, no further automatic retry (FR-007).
- [ ] **T202** [P] [US2] `MeltPaymentSentBurnFailedIT`: `pay` succeeds; the `PENDING → SPENT` commit fails (inject via Mockito on the proof port). Assert saga ⇒ `PAYMENT_SENT_BURN_FAILED`, proofs stay `PENDING`, no auto-retry of `gateway.pay`, structured-log alert emitted.
- [ ] **T203** [P] [US2] `MeltPaymentUnknownIT`: `pay` returns `Unknown`. Assert saga ⇒ `PAYMENT_UNKNOWN`. Run reconciler against a `MockLightningPaymentPort.checkStatus` that returns `Unknown` for N polls then `Success` — assert saga eventually advances to `COMPLETED`. Run a second variant where `checkStatus` never converges — assert saga stays in `PAYMENT_UNKNOWN` past TTL and an operator alert fires.
- [ ] **T204** [P] [US2] `MeltConcurrentSameQuoteIT`: two concurrent melt requests for the same `quote_id`. Assert exactly one saga is created; the second receives `melt_in_progress` (FR-005).
- [ ] **T205** [P] [US2] `MeltNut08OverpayIT`: proof sum > invoice + fee reserve. Happy path completes; change return computed from the persisted saga record after `COMPLETED` (FR-013).
- [X] **T206** [P] [US2] Unit test `MeltSagaStateMachineTest` covering every legal transition.

### Implementation for User Story 2

- [X] **T210** [US2] Add `LightningPaymentTimeout` configuration property (`cashu.mint.melt.payment-timeout`, default `PT30S`) and `ProofsHeldTtl` (default `PT5M`) per research R9.
- [X] **T211** [US2] Extend `MeltTask.java` (already modified in T112) to drive the saga machine:
  1. After `BurnAmountValidator.requireFunded`, insert `MeltSagaEntity` (`current_state = PROOFS_HELD`) AND mark all input proofs `UNSPENT → PENDING` with `melt_saga_id = sagaId` in a single `@Transactional` boundary. Append `MeltSagaTransition(seq=1, null → PROOFS_HELD)`.
  2. Call `LightningPaymentPort.pay(quoteId, timeout)`.
  3. On `Success`: CAS `PROOFS_HELD → PAYMENT_SENT` + record transition; commit proofs `PENDING → SPENT` + clear `melt_saga_id` + CAS `PAYMENT_SENT → COMPLETED` in a single transaction. If the final commit fails, CAS into `PAYMENT_SENT_BURN_FAILED` + record transition + fire operator alert.
  4. On `DefinitiveFailure`: CAS `PROOFS_HELD → FAILED` + return proofs `PENDING → UNSPENT` + clear `melt_saga_id`. Record transition.
  5. On `Unknown`: CAS `PROOFS_HELD → PAYMENT_UNKNOWN`. Record transition. Fire operator alert. Return `payment_unknown` response to the client.
- [X] **T212** [US2] Wire `MeltSagaReconciler` (skeleton from T060) to poll `LightningPaymentPort.checkStatus(quoteId)` for every saga in `PAYMENT_UNKNOWN`. On definitive resolution, CAS into `COMPLETED` (with proof commit) or `FAILED` (with proof refund). On TTL expiry without resolution, fire operator alert; saga stays in `PAYMENT_UNKNOWN` for operator action.
- [X] **T213** [P] [US2] Add `PROOFS_HELD` TTL sweep (also in `MeltSagaReconciler`): sagas older than `cashu.mint.melt.proofs-held-ttl` that have not advanced ⇒ CAS to `FAILED` + refund proofs.
- [X] **T214** [US2] Add structured-log + Micrometer counters for every state transition: `cashu_mint_melt_state_transitions_total{from, to}`. Operator alerts via log prefix `[melt-saga][alert]` (FR-011, FR-012).
- [ ] **T215** [US2] Implement NUT-08 overpaid-melt change return in `MeltTask` after `COMPLETED`: compute change against the persisted saga (`input_amount - invoice_amount - exact_fee_reserve`), issue blinded signatures, persist `change_outputs_hash` + `change_signatures_json` on the saga, return in the melt response (FR-013).
- [X] **T216** [US2] Persist `melt_response_cache` on every terminal transition for NUT-19 cached-responses replay (research R7).

**Checkpoint**: US1 + US2 both pass. The happy path produces a `COMPLETED` saga with full transition timeline; failure modes land in explicit compensation states with operator alerts.

---

## Phase 5: User Story 3 — Saga State Observable and Recoverable (Priority: P2)

**Goal**: Admin-only REST endpoint returns the saga's current state and transition timeline for a given `quote_id` or `melt_saga_id`. Operator reconciliation actions are append-only.

**Independent Test**: `MeltSagaQueryIT` queries the new endpoint and asserts the response shape matches the `MeltSagaTransition` timeline. Calls without admin credentials return 403.

### Tests for User Story 3

- [ ] **T300** [P] [US3] `MeltSagaQueryIT`: happy path + 401/403 paths + querying by `quote_id` vs `melt_saga_id`.
- [X] **T301** [P] [US3] `OperatorReconciliationIT`: from a `PAYMENT_SENT_BURN_FAILED` saga, the operator endpoint accepts a `mark_resolved` action that appends a transition (does NOT overwrite — append-only contract).
- [X] **T302** [P] [US3] Contract test on the response JSON shape (`/admin/melt-saga/{id}`) — pinned to the OpenAPI doc / Javadoc.

### Implementation for User Story 3

- [X] **T310** [US3] Add `MeltSagaAdminController` in `cashu-mint-rest/src/main/java/.../rest/admin/MeltSagaAdminController.java`: `GET /admin/melt-saga/by-id/{meltSagaId}`, `GET /admin/melt-saga/by-quote/{quoteId}`, `POST /admin/melt-saga/{id}/mark-resolved`.
- [ ] **T311** [US3] Apply Spring Security config so the new endpoints are reachable only by admin service-account principals (same mechanism as cashu-mint-admin-rest).
- [X] **T312** [US3] Response DTO `MeltSagaResponse` includes `currentState`, `quoteId`, `invoiceAmount`, `exactFeeReserve`, `inputAmount`, `proofCount`, `paymentHash`, `providerEventId`, `transitions: List<TransitionEntry>` (each: `seq`, `fromState`, `toState`, `reason`, `actor`, `at`).
- [X] **T313** [P] [US3] Document the endpoint in `cashu-mint-rest/README.md` (or top-level docs); call out that the endpoint is internal-only.

**Checkpoint**: US1 + US2 + US3 all pass independently. Operator reconciliation actions are auditable through the existing `MeltSagaTransition` table.

---

## Phase 6: Polish & Cross-Cutting

- [X] **T900** [P] [X] Run `mvn -q verify -P integration-tests`; attach IT report.
- [ ] **T901** [P] [X] Pin Javadoc on `MeltTask`, `BurnAmountValidator`, `LightningPaymentPort`, `MeltSagaReconciler` to NUT-05 / NUT-08 commit hashes (Constitution II, FR-014).
- [X] **T902** [P] [X] Update `CLAUDE.md` `## Architecture` to mention the saga state machine and `LightningPaymentPort` abstraction.
- [X] **T903** [P] [X] Daily reconciliation queries (operator dashboard SQL) embedded as Javadoc on `MeltSagaJpaRepository`:
  ```sql
  -- SC-002: every COMPLETED saga has all proofs in SPENT
  SELECT s.melt_saga_id FROM melt_saga s
   WHERE s.current_state='COMPLETED'
     AND EXISTS (SELECT 1 FROM proof_entity p WHERE p.melt_saga_id = s.melt_saga_id);
  -- expected: 0 rows
  ```
- [X] **T904** [X] Extend `LongArithmeticArchTest` (T004) to cover `MeltTask`, `BurnAmountValidator`, `ExactFeeReserveResolver`, `MeltSagaEntity` — guards FR-009.
- [ ] **T905** [X] Manual smoke against staging: drive a 100 sat invoice from a paying wallet; observe the saga lifecycle in the operator endpoint.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: no internal deps; external dep on **spec 001 foundational tasks landing first** (cashu-mint-jpa exists). Phase 1 also has an external prereq on the cashu-vault `melt_saga_id` column (T010) — that PR MUST land before US2 implementation can pass IT.
- **Phase 2**: depends on Phase 1.
- **Phase 3 (US1)**: depends on Phase 2 entities/ports but not on US2.
- **Phase 4 (US2)**: depends on Phase 2 + Phase 3 (`MeltTask` modifications). Can be developed in parallel with US1 if the developer drafts US2 against a stub `BurnAmountValidator`.
- **Phase 5 (US3)**: depends on Phase 4 (the saga entities are populated by US2).
- **Phase 6 (Polish)**: depends on US1+US2+US3 landed.

### Within Each User Story

- Tests first (Constitution IV).
- Validator before task wiring.
- Port implementation before scheduler.
- State machine fully implemented before operator endpoint.

### Parallel Opportunities

- All `[P]` Phase 1 and Phase 2 tasks parallel.
- US1 implementation can start while US2 tests are being written (different files).
- US3 can be picked up by a third developer once US2 entity writes are stable.

---

## Implementation Strategy

### MVP (US1 only — burn-amount check fix)

1. Phase 1 → Phase 2 → US1 (T100–T114).
2. Deploy to staging; observe `cashu_mint_melt_insufficient_input_total`. If non-zero, the gate is actively rejecting under-funded melts.
3. **Important**: US1 alone does NOT fix the burn-first ordering — pay-before-burn still happens in production until US2 lands. Coordinate with operations team.

### Incremental rollout

1. MVP (US1) → deploy → observe.
2. Add US2 (burn-first + saga) → deploy → observe `cashu_mint_melt_state_transitions_total`.
3. Add US3 (operator endpoint) → deploy → wire operator dashboard.

### Parallel team

- Dev A: US1 + US2 (state machine is the most complex chunk; same dev keeps continuity).
- Dev B: cashu-vault `melt_saga_id` column (T010) + US3 admin endpoint.
- Joint: foundational + final verification.

---

## Notes

- The `PaymentOutcome` sealed type is the cornerstone — every code path through `MeltTask` after US2 lands MUST consume it. There is no fallback "treat as success".
- `MeltSagaReconciler` is the only auto-driver after a saga lands in `PAYMENT_UNKNOWN`. It MUST NOT call `gateway.pay` ever — only `checkStatus`.
- Cross-repo dependency on cashu-vault is real (T010). Track it in the PR description; if the vault column isn't ready, US2 IT will fail with a clear error (`melt_saga_id column missing`).
- The eventual payment-adapter `PaymentOutcome` return type (research R4 long-term) is **out of scope**; today the wrapper port owns the parsing.
- Cross-link to spec 001: this spec shares Hibernate Envers config and the `long` ArchUnit gate. Spec 001 must merge first.
