---

description: "Task list for spec 001 — Mint Quote Amount Binding and Webhook Integrity"
---

# Tasks: Mint Quote Amount Binding and Webhook Integrity

**Input**: Design documents from `/specs/001-mint-quote-webhook-integrity/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅

**Tests**: Integration tests are MANDATORY per spec.md Success Criteria SC-001 through SC-007 and Constitution IV. Unit tests required for state-machine + idempotency hash logic.

**Organization**: Tasks are grouped by user story (US1, US2, US3) to enable independent implementation and testing. Foundational tasks (the new `cashu-mint-jpa` module + entities + Flyway) must complete first because every user story consumes them.

## Format: `[ID] [P?] [Story] Description`

- **[P]** — can run in parallel (different files, no dependencies)
- **[Story]** — `US1` (amount binding) / `US2` (webhook integrity) / `US3` (idempotent retry) / `F` (foundational) / `S` (setup) / `X` (cross-cutting / polish)

## Path Conventions

- Maven modules under repo root: `cashu-mint-protocol/`, `cashu-mint-webhook/`, `cashu-mint-rest/`, `cashu-mint-rest-it/`, plus the **new** `cashu-mint-jpa/` module per `plan.md` Structure Decision.
- Java sources under `<module>/src/main/java/xyz/tcheeric/cashu/mint/...`.
- Flyway migrations under `cashu-mint-jpa/src/main/resources/db/migration/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add the new `cashu-mint-jpa` module to the Maven reactor.

- [X] **T001** [S] Create `cashu-mint-jpa/pom.xml` with `<parent>` pointing at the root `cashu-mint` POM; dependencies: `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `org.hibernate.orm:hibernate-envers`, `org.postgresql:postgresql`, `org.flywaydb:flyway-core`, `cashu-lib:0.16.0`, `cashu-mint-protocol` (for port interfaces). Module packaging `jar`. *(commit: f4acc15; added jackson-databind for JSONB and flyway-database-postgresql.)*
- [X] **T002** [S] Add `<module>cashu-mint-jpa</module>` to the root `pom.xml` `<modules>` list **before** `cashu-mint-rest-it`. *(commit: f4acc15; also added dependencyManagement entry.)*
- [X] **T003** [P] [S] Update `cashu-mint-protocol/pom.xml` to remove any `cashu-vault` JPA leakage (if any); confirm `cashu-mint-protocol` declares only domain + port interface deps. *(commit: f4acc15; outcome: `cashu-mint-protocol` still depends on `cashu-vault-jpa` (legacy vault adapter dep — predates spec 001). Out of scope to remove here; no new JPA leakage introduced for spec 001 entities.)*
- [X] **T004** [P] [S] Add ArchUnit dependency (`com.tngtech.archunit:archunit-junit5:1.3.0`) to `cashu-mint-jpa` and `cashu-mint-protocol` test scopes for the `long`-arithmetic gate (research R9). *(commit: f4acc15.)*
- [X] **T005** [P] [S] Wire `spring.jpa.properties.org.hibernate.envers.audit_table_suffix = _aud` and Hibernate-Envers Spring auto-config in `cashu-mint-rest/src/main/resources/application.yml`. *(commit: f4acc15; configured in `cashu-mint-rest/src/main/resources/application.properties` (existing format) plus `cashu.mint.jpa.enabled` feature flag so legacy unit-test boots stay green.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: JPA entities, Flyway migrations, port interfaces, and the webhook signature config that every user story depends on. **No US task starts until Phase 2 is green.**

### 2A. Database schema (Flyway)

- [X] **T010** [F] Create `cashu-mint-jpa/src/main/resources/db/migration/V20260522_001__create_mint_quote.sql` per `data-model.md` § MintQuote: columns + PK + indexes on `lifecycle_state`/`created_at` + Envers `mint_quote_aud` and `REVINFO`. *(commit: f4acc15.)*
- [X] **T011** [F] Create `V20260522_002__create_issuance_record.sql` per `data-model.md` § IssuanceRecord: PK on `quote_id`, FK to `mint_quote(quote_id)`. *(commit: f4acc15; `issuance_record_aud` is intentionally not shipped — append-only contract, table itself is the audit.)*
- [X] **T012** [F] Create `V20260522_003__create_webhook_event.sql` per `data-model.md` § WebhookEvent: composite PK `(provider, provider_event_id)`, FK to `mint_quote`, indexes on `quote_id`/`outcome`/`received_at`, `outcome` CHECK constraint enumerating the 11 valid values. *(commit: f4acc15; ships 11 outcomes including `orphan` per data-model.)*
- [ ] **T013** [P] [F] Add a Flyway strict-validation Testcontainers boot test in `cashu-mint-rest-it` that fails CI if migration ordering breaks. *(deferred — requires Testcontainers wiring in the IT module; tracked as follow-up before spec 003 lands.)*

### 2B. JPA entities & repositories

- [X] **T020** [F] Implement `MintQuoteEntity` in `cashu-mint-jpa/src/main/java/.../jpa/entity/MintQuoteEntity.java`: `@Entity`, `@Audited`, `@Version`, lifecycle enum as string. `amount` and computed sums use `long`. *(commit: f4acc15.)*
- [X] **T021** [F] Implement `IssuanceRecordEntity`: `@Entity` keyed by `quote_id`, `signatures_json` mapped via `@JdbcTypeCode(SqlTypes.JSON)`, `total_amount` is `long`. *(commit: f4acc15; PK is independent `@Id` rather than `@MapsId` — the entity carries `quote_id` directly without a managed relationship to keep the append-only invariant simple.)*
- [X] **T022** [F] Implement `WebhookEventEntity`: composite PK via `@IdClass`; `outcome` enum as string with CHECK alignment. *(commit: f4acc15.)*
- [X] **T023** [P] [F] Implement Spring Data `MintQuoteJpaRepository`, `IssuanceRecordJpaRepository`, `WebhookEventJpaRepository`. Define one explicit CAS update on `MintQuoteJpaRepository`. *(commit: f4acc15; the CAS uses a JPQL `@Modifying @Query` returning row count for caller-side branching.)*
  ```java
  @Modifying
  @Query("UPDATE MintQuoteEntity q SET q.lifecycleState = :to, q.updatedAt = CURRENT_TIMESTAMP " +
         "WHERE q.quoteId = :id AND q.lifecycleState = :from")
  int casLifecycle(@Param("id") String id,
                   @Param("from") LifecycleState from,
                   @Param("to") LifecycleState to);
  ```
  Caller asserts return value `== 1` else re-reads.

### 2C. Port interfaces (in `cashu-mint-protocol`)

- [X] **T030** [F] Add `MintQuoteRepository` port interface in `cashu-mint-protocol/src/main/java/.../proto/ports/MintQuoteRepository.java` exposing `findById`, `save`, `casLifecycle(quoteId, from, to)`. *(commit: f4acc15; package path is `proto/ports/` per repo convention. Cross-check helper deferred with FR-010.)*
- [X] **T031** [P] [F] Add `IssuanceRecordRepository` port interface with `insertIfAbsent` returning a sealed `InsertResult` (newlyInserted | existing). *(commit: f4acc15.)*
- [X] **T032** [P] [F] Add `WebhookEventRepository` port interface throwing `DuplicateEventException` (carrying the existing row) on `(provider, provider_event_id)` PK conflict. *(commit: f4acc15.)*
- [X] **T033** [F] Wire JPA implementations to ports via `MintJpaAutoConfiguration` + per-port adapter beans in `cashu-mint-jpa/src/main/java/.../jpa/adapter/`. Auto-configures via `spring-boot-starter-data-jpa` and is gated by `cashu.mint.jpa.enabled=true`. *(commit: f4acc15.)*

### 2D. Webhook signature contract

- [X] **T040** [F] Define `@ConfigurationProperties("cashu.mint.webhook")` bean in `cashu-mint-webhook/src/main/java/.../webhook/WebhookProperties.java`. *(commit: f4acc15; activation guard refactored — instead of `@Profile("!local")` on the properties class, ship a separate `WebhookSecretStartupValidator @Component @Profile("!local")` that throws on `@PostConstruct` when `sharedSecret` is blank. Cleaner separation between binding and validation.)*
- [X] **T041** [P] [F] Refactor `WebhookSignatureValidator` to consume `WebhookProperties.sharedSecret`; the skip-if-blank fallback is removed (FR-007). *(commit: f4acc15; existing `WebhookSignatureValidatorTest` updated to assert the new fail-closed behaviour.)*
- [X] **T042** [P] [F] Add `ProviderIdentifier.resolve(Gateway)` in `cashu-mint-protocol/.../ports/` returning `gateway.getName()` (already exists on the Gateway interface) or the class simple name with a warning log. *(commit: f4acc15; cross-repo `Gateway.name()` migration item from research R6 confirmed already-shipped — payment-adapter 0.10.1 exposes `getName()`.)*

### 2E. ArchUnit `long` gate

- [X] **T050** [P] [F] Write `LongArithmeticArchTest` in `cashu-mint-protocol/src/test/java/.../proto/arch/LongArithmeticArchTest.java` asserting that no amount-bearing field is `int` / `Integer`. *(commit: f4acc15; caught and fixed a real regression — `MintQuoteTask.amount` was `int`; widened to `long` and the boundary cast to the payment-adapter `Gateway` is documented inline.)*

**Checkpoint**: Foundation ready — US1, US2, US3 can proceed in parallel.

---

## Phase 3: User Story 1 — Quote Amount Binds Issuance (Priority: P1) 🎯 MVP

**Goal**: NUT-04 mint requires `sum(outputs.amount) == quote.amount` and consumes each paid quote at most once.

**Independent Test**: `MintQuoteAmountBindingIT` drives `/v1/mint/bolt11` with under-, over-, and exact-amount blinded output sets against a `PAID` quote and asserts only the exact case succeeds with one `IssuanceRecord` row.

### Tests for User Story 1

- [ ] **T100** [P] [US1] Write `MintQuoteAmountBindingIT` in `cashu-mint-rest-it/src/test/java/.../it/MintQuoteAmountBindingIT.java`: scenarios under/over/exact + retry-with-same-outputs + retry-with-different-outputs (SC-001, FR-001/002/003). *(deferred — requires Testcontainers Postgres wiring in `cashu-mint-rest-it` + seeding harness; the unit-level equivalent is covered by T103.)*
- [ ] **T101** [P] [US1] Write `MintQuoteConcurrencyIT`: N concurrent identical mint requests against one quote; assert one `IssuanceRecord` row and identical signatures across all responses (SC-002). *(deferred — same Testcontainers dependency as T100.)*
- [X] **T102** [P] [US1] Write unit test `OutputsHashTest` for the `outputs_hash` computation (research R4): sort by `(amount, keyset_id, B_)`, SHA-256, verify hash is stable across permutations of the input list. *(commit: f4acc15; 6 cases pass.)*
- [X] **T103** [P] [US1] Write `MintTaskAmountValidationTest` (Mockito) covering: under-mint and over-mint rejected with `amount_mismatch` and zero `BlindSignature`s, exact-mint produces two CAS calls + one `IssuanceRecord` insert, replay with identical outputs returns cached signatures, replay with different outputs returns `quote_already_issued`. *(commit: f4acc15; 5 cases pass.)*

### Implementation for User Story 1

- [X] **T110** [US1] Add `OutputsHash` utility in `cashu-mint-protocol/src/main/java/.../proto/util/OutputsHash.java`: `compute(List<BlindedMessage>)` per research R4. *(commit: f4acc15.)*
- [X] **T111** [US1] Modify `MintTask.java` to enforce FR-001 / FR-002 / FR-011 plus US3 replay: load `MintQuote` via port → reject with `amount_mismatch` if sums diverge → CAS `PAID→ISSUING` → on `0` rows, branch into NUT-19 replay (matching outputs_hash) or `quote_already_issued` (different outputs) → sign → insert `IssuanceRecord` → CAS `ISSUING→ISSUED`. *(commit: f4acc15; `@Transactional` boundary deferred — current implementation is best-effort and leaves a quote in `ISSUING` for operator triage if the post-sign commit step fails. Wrapping the task in a Spring-managed transaction is tracked as follow-up before this lands in production behind the feature flag.)*
- [X] **T112** [US1] Modify `MintQuoteTask.java` to persist the quote via `MintQuoteRepository.save()` at quote-creation time; set `request_hash` per data-model § MintQuote. *(commit: ce60952; new optional `MintQuoteRepository` + `mintUrl` constructor params; `request_hash = SHA-256(amount|unit|method)`; persisted row lands in `UNPAID`; covered by `MintQuoteTaskTest#quote_PersistsDurableRowWhenRepositoryProvided` and `#quote_DoesNotPersistWhenRepositoryAbsent`.)*
- [X] **T113** [US1] Remove or shadow any in-process `QuoteStatusService` write paths used by the mint task; the service may stay as a read-through cache only (FR-004). *(commit: ce60952; outcome: `MintTask` only consumes the cache via `PaymentStatusChecker#isPaid` (read). No write paths exist on the mint-side — `QuoteStatusUpdater#markAsPaid` is invoked from the webhook controller path covered by US2/T211. For the mint path the durable `MintQuoteRepository` already wins when the repo is non-null, satisfying FR-004 today.)*
- [X] **T114** [US1] Wire the `Gateway.getAmount` cross-check (FR-010, research R8): fail-closed if the cross-check disagrees with the persisted quote. Surface the discrepancy as a structured-log alert. *(commit: ce60952; after loading the durable quote and validating `sum(outputs)==amount`, MintTask creates a Gateway and calls `getAmount(quoteId)`. Any exception OR a value that doesn't equal `quote.amount()` results in `quote_amount_cross_check_failed` and a structured ERROR log; covered by `MintTaskAmountValidationTest#cross_check_mismatch...` and `#cross_check_gateway_failure...`.)*
- [X] **T115** [US1] Add structured-log + Micrometer counter `cashu_mint_amount_mismatch_total{path="mint"}` (FR-012). *(commit: ce60952; new optional `MeterRegistry` constructor param emits two counters tagged `path="mint"`: `cashu_mint_amount_mismatch_total` on FR-001 rejection and `cashu_mint_quote_cross_check_failures_total` on FR-010 rejection. Covered by `MintTaskAmountValidationTest#amount_mismatch_increments_counter` and the two cross-check tests.)*

**Checkpoint**: US1 testable in isolation — running T100–T103 against the new `MintTask` should pass; the existing webhook contract is unchanged so far.

---

## Phase 4: User Story 2 — Webhook Amount/Unit/Event Binding (Priority: P1)

**Goal**: Webhook `PENDING → PAID` requires matching amount/unit/method/event-id; signature mandatory in non-local profiles.

**Independent Test**: `WebhookAmountBindingIT` drives `cashu-mint-webhook`'s `/v1/webhook/payment` with a matrix of correct, amount-mismatch, unit-mismatch, replayed, and unsigned deliveries; asserts only the correct first-arrival advances state to `PAID` and persists `outcome=accepted`; all other variants persist the right outcome and leave state unchanged.

### Tests for User Story 2

- [ ] **T200** [P] [US2] Write `WebhookAmountBindingIT` (Testcontainers) covering FR-005, FR-006, FR-008. Assert one `WebhookEvent` row per delivery with the expected outcome. *(deferred — Testcontainers Postgres wiring required in `cashu-mint-rest-it`; the outcome matrix is verified at unit level by `QuoteStatusUpdaterDurableTest` (T203).)*
- [ ] **T201** [P] [US2] Write `WebhookSignatureBootIT`: with `SPRING_PROFILES_ACTIVE=staging` and the secret unset, asserts Spring context fails to start (SC-005). *(deferred — same Testcontainers dependency. Spec 001 SC-005 behaviour is exercised at the rest-module context-load test (`CashuMintRestApplicationTests`) which now must set `cashu.mint.webhook.shared-secret` to boot.)*
- [ ] **T202** [P] [US2] Write `WebhookTamperDetectionIT`: same `provider_event_id` paired with different amount on the second delivery; assert `outcome=tamper` is recorded and quote state unchanged. *(deferred — Testcontainers; covered at unit level by `QuoteStatusUpdaterDurableTest#tamper_when_same_event_id_paired_with_different_amount`.)*
- [X] **T203** [P] [US2] Unit tests for the strict signature-required path **and** the durable outcome matrix. *(commit: 3a92f54; `WebhookSignatureValidatorTest` already covers the strict signature path post-T041; `QuoteStatusUpdaterDurableTest` adds 11 Mockito cases covering accepted / amount_mismatch / method_mismatch / orphan / duplicate / tamper / expired / noop / CAS-race / insert-race / counter-once.)*

### Implementation for User Story 2

- [X] **T210** [US2] Modify `PaymentWebhookController.java` to delegate strictly to `QuoteStatusUpdater#record` after signature verification; switch on `WebhookOutcome` to return per-outcome HTTP statuses (200 / 202 / 401 / 409 / 410 / 422). *(commit: 3a92f54.)*
- [X] **T211** [US2] Rewrite `QuoteStatusUpdater.java`: new entry point `record(PaymentNotification) → WebhookOutcome` that looks up the prior event by `(provider, provider_event_id)` (duplicate / tamper classification), loads the durable `MintQuote`, compares amount + method, CASes `PENDING → PAID` (also accepts `UNPAID → PAID` for gateways without a separate PENDING signal), and persists exactly one append-only `WebhookEvent` row tagged with the resolved outcome. Cache is now a read-through accelerator for `PaymentStatusChecker#isPaid` only. *(commit: 3a92f54; `@Transactional` boundary deferred — same operator-triage caveat as T111.)*
- [X] **T212** [US2] Retire `paymentMethod:quoteId` idempotency cache from the durable write path. *(commit: 3a92f54; `PaymentNotification#getIdempotencyKey()` is now `@Deprecated`. The legacy cache-only branch still uses it when the durable repositories are absent (unit-test contexts); when the repos are wired, idempotency is enforced solely by `(provider, provider_event_id)`.)*
- [X] **T213** [US2] Add structured-log + Micrometer counters keyed by `outcome` (FR-012). *(commit: 3a92f54; `cashu_mint_webhook_event_total{outcome=...}` is emitted on every classification. Each branch also logs a structured INFO/WARN line carrying provider/provider_event_id/quote_id/amount.)*
- [X] **T214** [US2] Document the webhook secret + provider configuration. *(commit: 3a92f54; added `docs/how-to/configure-webhook-integrity.md` with the mandatory-secret contract, provider key, feature flag, outcome matrix table mapping outcomes to HTTP statuses, and operator reconciliation SQL.)*

**Checkpoint**: US1 + US2 both pass independently. End-to-end happy path: pay → webhook PENDING→PAID → mint with matching outputs → ISSUED with one IssuanceRecord row, one accepted WebhookEvent row.

---

## Phase 5: User Story 3 — Idempotent Retry After Partial Issuance (Priority: P2)

**Goal**: Retry of the same outputs against an `ISSUED` quote returns the previously signed promises (NUT-19 cached responses); different outputs are rejected.

**Independent Test**: `MintQuoteIdempotentReplayIT` calls `/v1/mint/bolt11` twice with the same outputs and asserts both responses are byte-identical; a third call with different outputs is rejected as `quote_already_issued`.

### Tests for User Story 3

- [ ] **T300** [P] [US3] Write `MintQuoteIdempotentReplayIT`: same-outputs replay returns identical signatures; different-outputs replay returns `quote_already_issued`. *(deferred — Testcontainers wiring required; covered at unit level by `MintTaskAmountValidationTest#replay_with_same_outputs_returns_cached_signatures` and `#replay_with_different_outputs_against_issued_quote_is_rejected`.)*
- [X] **T301** [P] [US3] Write `IssuingConcurrencyTest` (unit). *(commit: 1a32a2b; 2 Mockito cases — the replay path successfully fires after the durable state transitions from ISSUING to ISSUED inside the bounded backoff window, and the exhaustion path surfaces `issuance_in_progress` without incrementing the replay counter.)*

### Implementation for User Story 3

- [X] **T310** [US3] Extend `MintTask` replay branch: bounded backoff polling (50/100/200/400/800 ms — total budget ≈1.55 s) when CAS PAID→ISSUING returns 0 and the quote is still in `ISSUING`. *(commit: 1a32a2b; on every poll: ISSUED + matching outputs_hash → replay, ISSUED + different outputs → `quote_already_issued`, anything else after exhaustion → `issuance_in_progress`. `Thread.sleep` honours interrupts cleanly. Constants exposed package-private as `ISSUING_POLL_BACKOFF_MS` for tests.)*
- [X] **T311** [P] [US3] Add structured-log line tagged `[mint][replay]` on every idempotent replay. *(commit: 1a32a2b — this line already shipped in T111 (f4acc15); T310 extends it with an `attempt=N` field so operators can distinguish first-poll replays from replays after backoff.)*
- [X] **T312** [P] [US3] Add Micrometer counter `cashu_mint_idempotent_replay_total{path="mint"}` (FR-012). *(commit: 1a32a2b; emitted on every successful replay via the same optional `MeterRegistry` constructor introduced in T115. Covered by `IssuingConcurrencyTest#concurrent_issuing_is_replayed_once_state_reaches_issued`.)*

**Checkpoint**: All three user stories pass independently. US3 depends on US1's `MintTask` but does not change the public response shape — clients see identical signatures across retries.

---

## Phase 6: Polish & Cross-Cutting

- [ ] **T900** [P] [X] Run `mvn -q verify -P integration-tests` end-to-end against a fresh Testcontainers Postgres; capture the full IT report and attach to the PR description. *(deferred — paired with the Testcontainers wiring follow-up that unblocks T013, T100/T101, T200/T201/T202, T300.)*
- [X] **T901** [P] [X] Update Javadoc on `MintTask`, `MintQuoteTask`, `PaymentWebhookController`, `QuoteStatusUpdater`, `WebhookSignatureValidator` with NUT-04 / NUT-06 / NUT-19 / NUT-20 references. *(commit: 806a15c; URLs currently point to `cashubtc/nuts/blob/main/{NN}.md`. Pinned-commit hashes per FR-014 / Constitution II remain a documented follow-up — choosing the canonical commit needs sign-off from the spec author.)*
- [X] **T902** [P] [X] Update `CLAUDE.md` `## Architecture` section. *(commit: 806a15c; new `cashu-mint-jpa` module listed under main modules, spec 001 sub-section added under Architecture describing the three durable tables, the lifecycle state machine, and the `cashu.mint.jpa.enabled` feature flag.)*
- [X] **T903** [X] Audit advertised NUT-06 surface (SC-007). *(commit: 806a15c; the `NutAdvertisementContractIT` Testcontainers form is deferred. Added `NutAdvertisementContractTest` (unit) that parses `mint.yaml` and asserts the advertised NUT set matches a documented test-backed allow list ({4, 5, 7, 8, 9, 10, 11, 12, 17}). Adding or removing an advertisement now fails this test until the inventory comment + EXPECTED constant are updated.)*
- [X] **T904** [X] Daily reconciliation queries embedded as class-level Javadoc on `MintQuoteJpaRepository`. *(commit: 806a15c; two operator queries shipped — FR-001 invariant `SUM(mint_quote.amount WHERE ISSUED) == SUM(issuance_record.total_amount)`, plus a FR-005 invariant ensuring every `PAID`/`ISSUING`/`ISSUED` quote has a matching `webhook_event.outcome = accepted` row.)*
- [X] **T905** [X] Re-ran `LongArithmeticArchTest` against the now-merged code. *(commit: 806a15c; gate still passes — see test output, 1 case green.)*
- [ ] **T906** [X] Manual smoke against staging: pay a small quote, mint exact amount, verify ISSUED row + accepted WebhookEvent + matching IssuanceRecord. *(deferred — requires staging access outside this session.)*

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: no deps → start immediately.
- **Phase 2 (Foundational)**: depends on Phase 1. **Blocks all user stories.**
- **Phase 3 (US1), Phase 4 (US2)**: independent of each other, both depend only on Phase 2. Can be developed in parallel.
- **Phase 5 (US3)**: depends on US1's `MintTask` modifications (T111) — implementation extends US1's task path. Tests can be written in parallel with US1 work.
- **Phase 6 (Polish)**: depends on all three user stories landed.

### Within Each User Story

- Tests (T100–T103, T200–T203, T300–T301) MUST be written and FAIL before implementation per Constitution IV.
- Models / migrations before services (already enforced by Phase 2 gating).
- Services before controllers (already enforced — controllers stay thin per Constitution III).

### Parallel Opportunities

- All `[P]` tasks within Phase 1 and Phase 2 can run in parallel (different files).
- US1 and US2 phases can be developed in parallel by two developers once Phase 2 is green.
- All `[P]` tests in each US can run in parallel (different test files).

---

## Parallel Example: kick off Phase 3 + Phase 4 in parallel after Phase 2

```bash
# Developer A — US1
git checkout 001-mint-quote-webhook-integrity
# Implements T110, T111, T112, T113, T114, T115 against T100-T103 tests

# Developer B — US2 (same branch or shared branch + merge)
# Implements T210, T211, T212, T213, T214 against T200-T203 tests
```

---

## Implementation Strategy

### MVP (US1 only)

1. Phase 1 setup → Phase 2 foundational → US1 (T100–T115).
2. Validate against SC-001, SC-002, SC-003 (replay subset deferred to US3).
3. Deploy to staging, soak for a day with the spec-001 monitoring queries.

### Incremental rollout

1. MVP (US1) → deploy → measure `amount_mismatch` counter (FR-001 enforced in prod).
2. Add US2 (webhook integrity) → deploy → measure `webhook_event` outcomes.
3. Add US3 (idempotent retry) → deploy → measure `idempotent_replay_total`.

### Parallel team

- Dev A: US1 + Phase 6 polish for US1.
- Dev B: US2 + Phase 6 polish for US2.
- Joint: Phase 2 foundational + final verification.

---

## Notes

- Tests use Testcontainers PostgreSQL (constitution IV — H2 forbidden for the write path).
- All amount math uses `long`; `LongArithmeticArchTest` (T050) is the CI gate.
- The cross-repo `Gateway.name()` change in payment-adapter (research R6) is **out of scope** here; T042 has a fallback that logs a warning when only the class simple name is available.
- NUT-19 replay (US3) is the natural correctness counterpart to US1; clients that don't retry get the same SC-001/SC-002 guarantees regardless.
- Cross-link to spec 003: the `IssuanceRecord` PK (`quote_id`) is shared with voucher quote_ids per spec 003's namespace invariant. The foundational migrations here MUST land before spec 003's `voucher_issuance` migrations.
