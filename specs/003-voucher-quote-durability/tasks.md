---

description: "Task list for spec 003 — Voucher Quote Durability and Funding-Source Binding"
---

# Tasks: Voucher Quote Durability and Funding-Source Binding

**Input**: Design documents from `/specs/003-voucher-quote-durability/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅. **Inherits `cashu-mint-jpa` from spec 001**; the `IssuanceRecord` table from spec 001 is the persistence surface for the voucher proof receipts via the namespace invariant (voucher quote_ids are disjoint from regular mint quote_ids).

**Tests**: Integration tests are MANDATORY per spec.md SC-001 through SC-006 and Constitution IV. Restart test for SC-002 is non-negotiable.

**Organization**: Tasks grouped by user story (US1, US2, US3). Phase 2 foundational covers voucher JPA entities (parent + 3 funding subclasses + issuance + idempotency key) plus migrations, port interfaces, the funding resolver, and the auth + rate-limit + idempotency middleware that all three stories share.

## Format: `[ID] [P?] [Story] Description`

- **[P]** — parallel-safe.
- **[Story]** — `US1` (funding-record-backed issuance) / `US2` (restart durability) / `US3` (auth + rate-limit + idempotency) / `F` (foundational) / `S` (setup) / `X` (polish).

## Path Conventions

- Same as spec 001/002. Voucher REST controller lives in `cashu-mint-rest`; voucher entities live in `cashu-mint-jpa`; tests in `cashu-mint-rest-it`.

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] **T001** [S] Confirm spec 001's `cashu-mint-jpa` and `IssuanceRecord` table are on the trunk. Abort if not.
- [ ] **T002** [P] [S] Add `org.springframework.boot:spring-boot-starter-security` dependency to `cashu-mint-rest` if not already present (research R3); also add `com.github.ben-manes.caffeine:caffeine` (already a Spring Boot transitive — verify).
- [ ] **T003** [P] [S] Extend the existing `LongArithmeticArchTest` (spec 001 T050) package scope to cover `tasks/VoucherMintQuoteTask`, `domain/Voucher*`, and `jpa/entity/Voucher*`.
- [ ] **T004** [P] [S] Add a `VoucherTestSupport` test fixture in `cashu-mint-rest-it/src/test/java/.../it/support/VoucherTestSupport.java` providing builders for `CustomerPaymentFunding` / `MerchantDebitFunding` / `MerchantIouFunding` test instances + matching `VoucherQuote` setups.

---

## Phase 2: Foundational (Blocking Prerequisites)

### 2A. Cross-repo prerequisite (imani-bridge)

- [ ] **T010** [F] Coordinate with imani-bridge spec backlog the gateway-side change in `WalletPluginAdapter` so voucher quote creation forwards `funding_ref` (a `provider_event_id` / `merchant_debit_id` / `iou_id`) AND `idempotency_key` to the mint. The imani-bridge PR MUST land in lock-step. (Add link to PR description.)

### 2B. Database schema

- [ ] **T020** [F] Create `V20260524_001__create_voucher_funding.sql` (FK target, must precede `voucher_quote`): parent `voucher_funding` table + three child tables `customer_payment_funding`, `merchant_debit_funding`, `merchant_iou_funding` with FK to parent. JOINED inheritance shape per data-model § VoucherFunding. UNIQUE on `(provider, provider_event_id)`, `(merchant_id, merchant_debit_id)`, `(merchant_id, iou_id)`. CHECK constraint on parent `funding_source` enumerating the 3 valid discriminator values. Envers `_aud` tables for each table.
- [ ] **T021** [F] Create `V20260524_002__create_voucher_quote.sql`: per data-model § VoucherQuote. PK on `quote_id`, partial UNIQUE on `idempotency_key WHERE idempotency_key IS NOT NULL`, FK `funding_id → voucher_funding(funding_id)`, indexes per data-model. CHECK constraint on `lifecycle_state`. Envers shadow.
- [ ] **T022** [F] Create `V20260524_003__create_voucher_issuance.sql`: per data-model § VoucherIssuance. PK on `voucher_quote_id`, FK to `voucher_quote` and `voucher_funding`. **Important**: the `issuance_id` FK target is the spec-001 `issuance_record(quote_id)` — but the `quote_id` namespace overlaps via the invariant ("voucher quote_ids are disjoint from mint quote_ids"). The FK is declared on `issuance_record(quote_id)`.
- [ ] **T023** [F] Create `V20260524_004__create_voucher_idempotency_key.sql`: per data-model § VoucherIdempotencyKey. PK `(idempotency_key, principal_id)`, index on `expires_at` for TTL sweep.

### 2C. Domain types

- [ ] **T030** [F] Add `VoucherQuoteType` enum extensions if needed: `CUSTOMER_PAID`, `MERCHANT_FUNDED`, `IOU` (today `VoucherQuoteType` exists; verify and adjust).
- [ ] **T031** [P] [F] Add `VoucherFundingSource` enum: `CUSTOMER_PAYMENT`, `MERCHANT_DEBIT`, `MERCHANT_IOU`.
- [ ] **T032** [P] [F] Add `VoucherLifecycleState` enum: `UNFUNDED`, `FUNDED`, `ISSUING`, `ISSUED`, `EXPIRED`, `FAILED`.

### 2D. JPA entities & repositories

- [ ] **T040** [F] Implement `VoucherFundingEntity` (parent) + three concrete subclasses (`CustomerPaymentFundingEntity`, `MerchantDebitFundingEntity`, `MerchantIouFundingEntity`) with `@Inheritance(JOINED)` and `@DiscriminatorColumn("funding_source")`. Amount fields `long`. `@Audited`.
- [ ] **T041** [P] [F] Implement `VoucherQuoteEntity` with `@Audited`, all amount fields `long`, lifecycle enum mapped as string, `@Version`.
- [ ] **T042** [P] [F] Implement `VoucherIssuanceEntity` (append-only) with `@MapsId` to `VoucherQuoteEntity`.
- [ ] **T043** [P] [F] Implement `VoucherIdempotencyKeyEntity` with composite ID.
- [ ] **T044** [F] Implement Spring Data repositories: `VoucherFundingJpaRepository`, `VoucherQuoteJpaRepository`, `VoucherIssuanceJpaRepository`, `VoucherIdempotencyKeyJpaRepository`. Add CAS update on `VoucherQuoteJpaRepository.casLifecycle` mirroring spec 001's idiom.

### 2E. Ports & resolvers

- [ ] **T050** [F] Define port interfaces in `cashu-mint-protocol/src/main/java/.../protocol/ports/`:
  - `VoucherQuoteRepository`
  - `VoucherFundingRepository`
  - `VoucherIssuanceRepository`
  - `VoucherIdempotencyKeyRepository`
  - `VoucherFundingResolver` — `Optional<VoucherFundingEntity> resolveForQuote(VoucherQuoteEntity quote)` encapsulates the policy (research R6): customer-paid resolves via the spec-001 `WebhookEvent` row; merchant-debit resolves via merchant-ledger client (out of scope here; stub interface); IOU resolves only when `cashu.mint.voucher.iou-policy = ALLOW`.
- [ ] **T051** [P] [F] Implement `VoucherFundingResolverImpl` in `cashu-mint-jpa/src/main/java/.../jpa/service/VoucherFundingResolverImpl.java`. Inject `WebhookEventJpaRepository` (spec 001) for the `CUSTOMER_PAYMENT` lookup; emit operator alert + Micrometer counter on `MERCHANT_IOU` issuance regardless of policy.
- [ ] **T052** [P] [F] Wire JPA repositories to ports via `JpaConfig` (extending spec 001).
- [ ] **T053** [P] [F] Add `@ConfigurationProperties("cashu.mint.voucher")` bean with `iouPolicy: ALLOW|DENY` (default `DENY`), `idempotencyKeyTtl: Duration` (default `PT24H`), per-principal `rateLimitTokensPerMinute: int` (default `60`).

### 2F. Middleware

- [ ] **T060** [F] `VoucherEndpointSecurityConfig` in `cashu-mint-rest/src/main/java/.../rest/security/VoucherEndpointSecurityConfig.java`: Spring Security configuration that requires authentication on every `/v1/voucher/**` route (research R3). Service-account JWT or merchant principal. Profile-aware: in `local`, may permit a developer bypass; in `staging`/`prod`, no bypass.
- [ ] **T061** [P] [F] `VoucherRateLimitFilter` in `cashu-mint-rest/src/main/java/.../rest/ratelimit/VoucherRateLimitFilter.java`: Caffeine-backed token-bucket per principal (research R4); rate limits from `cashu.mint.voucher.rate-limit-tokens-per-minute`. 429 on exhaustion.
- [ ] **T062** [P] [F] `VoucherIdempotencyKeyFilter` in `cashu-mint-rest/src/main/java/.../rest/idempotency/VoucherIdempotencyKeyFilter.java`: reads `Idempotency-Key` header on POST routes; persists a `voucher_idempotency_key` row keyed by `(key, principal_id, request_hash)`; on retry with same key + same hash, returns the cached response; on retry with same key + different hash, returns 409 `idempotency_key_conflict`. Sweep job (Spring `@Scheduled`) deletes expired rows.

### 2G. NUT-06 advertisement guard

- [ ] **T070** [P] [F] Audit the existing NUT-06 info builder (cashu-mint-rest) and assert vouchers are NOT under the `nuts` key. If they are, remove them and surface as a `vendor_extensions` map instead (FR-010).

**Checkpoint**: All entities, ports, middleware, and config in place. US1, US2, US3 can proceed.

---

## Phase 3: User Story 1 — Every Voucher Proof Backed by Durable Funding (Priority: P1) 🎯 MVP

**Goal**: Voucher proofs are issued only when one of three funding records exists (customer payment, merchant debit, IOU under policy).

**Independent Test**: `VoucherQuoteDurableIT` creates a voucher quote with no funding, attempts to mint, expects `funding_required` and zero signatures. Three positive variants exercise each funding source.

### Tests for User Story 1

- [ ] **T100** [P] [US1] `VoucherQuoteDurableIT`: scenarios (no funding, customer-paid funding settled, merchant-funded debit recorded, IOU with policy ALLOW, IOU with policy DENY). Assert correct outcome per scenario; assert `voucher_issuance` row exists with correct `funding_id` on success; assert zero `voucher_issuance` on rejection.
- [ ] **T101** [P] [US1] `VoucherFundingResolverTest` (unit): per funding source — happy path + failure modes (funding not found, amount mismatch, unit mismatch).
- [ ] **T102** [P] [US1] `VoucherFundingPolicyIT` (covers FR-006): `iou-policy=DENY` ⇒ creation of IOU-funded quote rejected at REQUEST time, not at MINT time.
- [ ] **T103** [P] [US1] `VoucherAuditTraceIT`: given an issued voucher proof, the audit query (`voucher_issuance JOIN voucher_funding`) returns the funding source in one hop (FR-005).

### Implementation for User Story 1

- [ ] **T110** [US1] Modify `VoucherMintQuoteTask.java` (`cashu-mint-protocol/src/main/java/.../protocol/tasks/VoucherMintQuoteTask.java`):
  1. Persist a `VoucherQuoteEntity` (`lifecycle_state = UNFUNDED`) at quote-creation time.
  2. Resolve the funding via `VoucherFundingResolver.resolveForQuote(quote)`.
  3. If a funding row exists, CAS `UNFUNDED → FUNDED` and set `voucher_quote.funding_id`.
  4. If IOU policy is `DENY`, reject quote creation with `iou_not_permitted` (FR-006).
- [ ] **T111** [US1] Modify the voucher branch of `MintTask.java` (the path triggered by `VoucherQuoteRegistry.isVoucherQuote(quoteId)` today):
  1. Load `VoucherQuoteEntity` by `quoteId`.
  2. If `voucher_quote.funding_id IS NULL` ⇒ reject with `funding_required` (FR-002).
  3. Validate `sum(outputs.amount) == face_value` (existing check; keep).
  4. CAS `FUNDED → ISSUING`. Insert spec-001 `IssuanceRecord` (the `quote_id` namespace is disjoint per research R1). Insert `VoucherIssuanceEntity` linking voucher quote → funding → issuance. CAS `ISSUING → ISSUED`. Single `@Transactional` boundary.
- [ ] **T112** [P] [US1] Demote `VoucherQuoteRegistry` to a read-through cache only. Remove any in-memory write decisions; the durable repository is the source of truth (FR-003).
- [ ] **T113** [US1] Wire spec 001's webhook flow to also insert a `CustomerPaymentFundingEntity` row whenever a webhook `outcome=accepted` lands for a quote that has a matching voucher quote in `UNFUNDED`. (Cross-link between specs: the webhook flow created in spec 001 needs a small extension here. Tracked in this spec to avoid expanding spec 001.)
- [ ] **T114** [P] [US1] Add structured-log + Micrometer counters: `cashu_mint_voucher_issued_total{funding_source}`, `cashu_mint_voucher_funding_required_total`. Operator alert on every `MERCHANT_IOU` issuance regardless of policy (FR-014).

**Checkpoint**: US1 testable in isolation. Every issued voucher traces to a funding row.

---

## Phase 4: User Story 2 — Voucher Quote State Survives Restart (Priority: P1)

**Goal**: Voucher quote classification, face value, charged amount, merchant identity, funding source, and lifecycle state survive a JVM restart.

**Independent Test**: `VoucherQuoteRestartIT` creates a voucher quote, restarts the application context (Testcontainers `@DirtiesContext` or container restart), then attempts to mint against the quote and asserts classification + face value match the pre-restart values (SC-002).

### Tests for User Story 2

- [ ] **T200** [P] [US2] `VoucherQuoteRestartIT`: full happy path with Spring context restart in the middle. Two variants — restart between quote-creation and funding-resolution, and restart between funding-resolution and mint.
- [ ] **T201** [P] [US2] `VoucherQuoteRegistryCacheTest` (unit): cold cache after restart re-hydrates from `VoucherQuoteRepository` on first read.

### Implementation for User Story 2

- [ ] **T210** [US2] Ensure `VoucherQuoteRegistry` reads from `VoucherQuoteRepository` on cache miss; remove any "in-memory only" fallback that would silently lose state at restart.
- [ ] **T211** [US2] Verify all writes in `VoucherMintQuoteTask` (modified in T110) commit before the response is returned (FR-001, FR-003). Add a Spring `@TransactionalEventListener` if any post-commit signalling is needed; in-flight state on the JVM heap MUST NOT be the source of truth.
- [ ] **T212** [P] [US2] Add structured-log line `[voucher-quote][persist]` on every quote persistence, with `quote_id` and `lifecycle_state` so support can trace state across restarts.

**Checkpoint**: US1 + US2 pass. Voucher quotes are durable across restart.

---

## Phase 5: User Story 3 — Voucher Endpoints Authenticated, Rate-Limited, Idempotent (Priority: P2)

**Goal**: Voucher REST endpoints require authentication, rate-limit per principal, and are idempotent on `Idempotency-Key`.

**Independent Test**: `VoucherEndpointAuthIT` covers unauth (401), wrong-role (403), happy path, rate-limit (429), idempotency-key replay (same response), idempotency-key conflict (409).

### Tests for User Story 3

- [ ] **T300** [P] [US3] `VoucherEndpointAuthIT`: 401 / 403 / happy paths (SC-003).
- [ ] **T301** [P] [US3] `VoucherEndpointRateLimitIT`: exhaust the per-principal bucket; verify 429 + retry-after header; verify the bucket refills.
- [ ] **T302** [P] [US3] `VoucherEndpointIdempotencyIT`: same key + same hash ⇒ cached response; same key + different hash ⇒ 409.
- [ ] **T303** [P] [US3] `VoucherEndpointBootIT`: in `staging` profile with security misconfigured (e.g. missing JWT issuer), startup fails.
- [ ] **T304** [P] [US3] `VoucherNutAdvertisementIT`: GET `/v1/info` (NUT-06) response's `nuts` key does NOT include voucher (SC-004); `vendor_extensions` MAY include it.

### Implementation for User Story 3

- [ ] **T310** [US3] Apply `VoucherEndpointSecurityConfig` (T060) to the voucher route group. Modify `VoucherController.java` (`cashu-mint-rest/src/main/java/.../rest/controller/VoucherController.java`) to consume `@AuthenticationPrincipal` for the calling principal; reject if missing.
- [ ] **T311** [P] [US3] Register `VoucherRateLimitFilter` (T061) for `/v1/voucher/**` routes. Surface remaining tokens via `X-RateLimit-Remaining` and `Retry-After` headers on 429.
- [ ] **T312** [P] [US3] Register `VoucherIdempotencyKeyFilter` (T062) for POST routes under `/v1/voucher/**`. Enforce that the `Idempotency-Key` header is REQUIRED on quote-creation and finalization; missing key ⇒ 400.
- [ ] **T313** [US3] Verify NUT-06 info builder (T070) and lock in the smoke test from T304.
- [ ] **T314** [P] [US3] Add `cashu_mint_voucher_rate_limit_breach_total` counter; operator alert on sustained breach (FR-014).
- [ ] **T315** [P] [US3] Update `cashu-mint-rest/README.md` documenting the new auth / rate-limit / idempotency contract for integrators.

**Checkpoint**: All three user stories pass independently.

---

## Phase 6: Polish & Cross-Cutting

- [ ] **T900** [P] [X] Run `mvn -q verify -P integration-tests`; attach the IT report. Pay special attention to `VoucherQuoteRestartIT` — restart-tests are flake-prone.
- [ ] **T901** [P] [X] Update Javadoc on `VoucherMintQuoteTask`, the voucher branch of `MintTask`, `VoucherFundingResolverImpl`, and the new entities with pinned-commit URLs to NUT-04 (FR-013, Constitution II). Note explicitly that vouchers are a non-standard extension.
- [ ] **T902** [P] [X] Operator dashboard layout: SQL snippets embedded as Javadoc on `VoucherIssuanceJpaRepository`:
  ```sql
  -- SC-001: every issued voucher traces to a funding row
  SELECT q.quote_id FROM voucher_quote q
   WHERE q.lifecycle_state = 'ISSUED'
     AND q.funding_id IS NULL;
  -- expected: 0 rows

  -- Operator IOU liability dashboard
  SELECT f.funding_source, SUM(f.amount)
  FROM voucher_funding f
  JOIN voucher_quote q ON q.funding_id = f.funding_id
   AND q.lifecycle_state = 'ISSUED'
  GROUP BY f.funding_source;
  ```
- [ ] **T903** [P] [X] Update `CLAUDE.md` `## Architecture` and / or `## Voucher System` sections: clarify vouchers are non-NUT vendor extensions; reference this spec.
- [ ] **T904** [X] Update operator runbook (if it exists) with the new dashboards and the IOU policy lever.
- [ ] **T905** [X] Manual smoke against staging: create a voucher quote (customer-paid path), settle the payment, mint, verify `voucher_issuance` row + matching `voucher_funding(CustomerPaymentFunding)` row.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: depends on **spec 001 foundational tasks landing**. Spec 001's `cashu-mint-jpa` + `IssuanceRecord` table are required.
- **Phase 2**: depends on Phase 1. External cross-repo dep on imani-bridge `WalletPluginAdapter` change (T010) — that PR MUST land before US1 implementation can pass IT for `funding_ref` propagation.
- **Phase 3 (US1)**: depends on Phase 2.
- **Phase 4 (US2)**: depends on Phase 3 (uses `VoucherMintQuoteTask` and `VoucherQuoteEntity`).
- **Phase 5 (US3)**: depends on Phase 2 middleware; can run in parallel with US1 + US2 once middleware is wired.
- **Phase 6**: depends on US1 + US2 + US3 landed.

### Within Each User Story

- Tests first.
- Funding resolver before voucher branch wiring.
- Spring Security config before rate-limit filter before idempotency filter (filters depend on the authenticated principal).
- Restart test is the load-bearing IT for US2; do not skip.

### Parallel Opportunities

- All `[P]` tasks within Phase 1 / Phase 2 parallel.
- US1 and US3 can be developed in parallel by two developers once Phase 2 is green.
- US2's restart test depends on US1 implementation; develop US2 tests in parallel with US1 implementation, run them when US1 is wired.

---

## Implementation Strategy

### MVP (US1 only — durable funding-record-backed issuance)

1. Phase 1 → Phase 2 → US1 (T100–T114).
2. Deploy to staging; observe `cashu_mint_voucher_issued_total{funding_source}` and `cashu_mint_voucher_funding_required_total`.
3. **Important**: until US2 + US3 land, voucher endpoints are not yet auth-hardened or rate-limited. Coordinate with operations team about acceptable interim posture.

### Incremental rollout

1. MVP (US1) → deploy → observe.
2. Add US2 (restart durability test as the regression gate) → deploy.
3. Add US3 (auth + rate-limit + idempotency) → deploy → observe rate-limit breaches.

### Parallel team

- Dev A: US1 + cross-repo imani-bridge coordination (T010).
- Dev B: Phase 2 middleware + US3.
- Joint: foundational entities + US2 restart test.

---

## Notes

- The `IssuanceRecord` table from spec 001 is the issuance ledger for both regular mint quotes AND voucher quotes — the namespace invariant from research R1 makes this safe. The audit JOIN in T903 goes `voucher_issuance → voucher_funding` and additionally JOINs `issuance_record` if blinded-output forensics are needed.
- Voucher endpoints MUST NOT be advertised under NUT-06's `nuts` key (FR-010). Use `vendor_extensions`.
- IOU policy is profile-level for v1 (research R6). Per-merchant policy is a future enhancement.
- The cross-repo imani-bridge change (T010) needs `funding_ref` and `idempotency_key` to be passed forward. Without it, US1's `funding_ref` lookup falls back to scanning `WebhookEvent` by quote_id (slower but functional).
- The voucher state machine (UNFUNDED → FUNDED → ISSUING → ISSUED) mirrors spec 001's MintQuote machine; reuse the CAS idiom.
- The `policy_profile` column on `MerchantIouFunding` (data-model) lets the operator detect when an IOU was issued under a profile whose policy has since changed; surface in operator dashboard.
- Cross-link to spec 001: this spec extends `WebhookEvent` flow with the voucher-funding insert (T113). If spec 001 and spec 003 land in different release trains, T113 has to coordinate.
