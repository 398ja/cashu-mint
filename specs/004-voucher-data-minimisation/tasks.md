---
description: "Task list for spec 004 — Voucher Data Minimisation and Customer-Identity Custody"
---

# Tasks: Voucher Data Minimisation and Customer-Identity Custody

**Input**: Design documents from `/specs/004-voucher-data-minimisation/`
**Prerequisites**: spec.md ✅, plan.md ✅, research.md ✅, data-model.md ✅, contracts/identity-hasher.md ✅, contracts/grafana-panels.md ✅, quickstart.md ✅. **Inherits cashu-mint-jpa + cashu-mint-observability from spec 001 + 003**.

**Tests**: Integration tests are MANDATORY per spec.md SC-001 through SC-012 and Constitution IV.

**Organization**: Tasks grouped by user story (US1-US6). Phase 2 foundational covers the `IdentityHasher` port + impl + 7 Flyway migrations + 2 new tables + the Grafana DB role + the salt-fail-closed boot validator. Stories run independently after Phase 2; the Grafana story (US6) depends on the data-model rows existing but not on US1-US5 implementation details.

## Format: `[ID] [P?] [Story] Description`

- **[P]** — parallel-safe (different files, no dependencies on incomplete tasks).
- **[Story]** — `US1` / `US2` / `US3` / `US4` / `US5` / `US6` / no-label for Setup / Foundational / Polish.

## Path Conventions

- Module layout from plan.md § Source Code. New files under `cashu-mint-jpa/src/main/java/.../jpa/crypto/`, `cashu-mint-jpa/src/main/java/.../jpa/service/`, `cashu-mint-rest/src/main/java/.../rest/admin/`, `cashu-mint-observability/docker/grafana/dashboards/`. Migrations under `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260601_*.sql`. Tests in `cashu-mint-rest-it/src/test/java/.../spec004/`.

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 Confirm PR #321 + #322 are merged to master and `003-voucher-quote-durability` is on the trunk. Abort if not — spec 004 depends on spec 003 entities + funding gate.
- [ ] T002 [P] Verify `javax.crypto.Mac` `HmacSHA256` is available on the project's JDK 21 runtime via a one-shot `mvn -pl cashu-mint-jpa test -Dtest=HmacSha256AvailabilityProbeTest` smoke probe (write the test).
- [ ] T003 [P] Add `cashu.mint.voucher.identity-salt`, `cashu.mint.voucher.identity-retention`, `cashu.mint.voucher.identity-backfill-batch-size`, `cashu.mint.voucher.identity-purge-cron` to `cashu-mint-rest/src/main/resources/application.properties` as commented defaults (`PT2160H`, `1000`, `0 0 3 * * *`). Document each in the file header comment.
- [ ] T004 [P] Add the spec-004 properties to `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/config/VoucherDurabilityProperties.java` — `identitySalt: String`, `identityRetention: Duration` (default `PT2160H`), `identityBackfillBatchSize: int` (default `1000`), `identityPurgeCron: String` (default `0 0 3 * * *`).

---

## Phase 2: Foundational (Blocking Prerequisites)

### 2A. Constitution ratification

- [ ] T010 Promote Principle VII (Data Minimisation and Customer-Identity Custody) in `.specify/memory/constitution.md` from `1.2.0-draft` to `1.2.0` — remove the DRAFT banner, update the Sync Impact Report, set the version line.

### 2B. IdentityHasher port + impl

- [ ] T020 Define port interface `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/ports/IdentityHasher.java` per `contracts/identity-hasher.md` § Port interface. Single method `String hash(String value)`. Javadoc references FR-002 + Clarifications Q1 + Q3.
- [ ] T021 Implement `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/crypto/HmacSha256IdentityHasher.java` per `contracts/identity-hasher.md` § Default implementation contract. Uses `Mac.getInstance("HmacSHA256")` with `ThreadLocal<Mac>` reuse. Reads salt from `cashu.mint.voucher.identity-salt` via constructor injection. `@PostConstruct` validates non-null + ≥ 32 bytes; throws `IllegalStateException` on violation. `@Component` gated on `@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")`.
- [ ] T022 [P] Add boot-time fail-closed integration test `cashu-mint-rest-it/src/test/java/xyz/tcheeric/cashu/mint/rest/spec004/HmacSha256IdentityHasherBootIT.java` — asserts Spring context fails with `IllegalStateException` when salt env var unset OR salt < 32 bytes. Uses `ApplicationContextRunner` per spec 001 idiom.
- [ ] T023 [P] Add unit test `cashu-mint-jpa/src/test/java/xyz/tcheeric/cashu/mint/jpa/crypto/HmacSha256IdentityHasherTest.java` covering the 6 behaviour-table cases from `contracts/identity-hasher.md` § Required behaviour table (null short-circuit, empty short-circuit, deterministic, 64-char hex, distinct outputs, salt sensitivity).
- [ ] T024 [P] Add microbenchmark test `cashu-mint-jpa/src/test/java/xyz/tcheeric/cashu/mint/jpa/crypto/HmacSha256IdentityHasherBenchmarkTest.java` — 100k iterations, asserts p99 < 1ms (SC-006). JMH-lite using `System.nanoTime` rather than full JMH harness.

### 2C. Flyway migrations (in order)

- [ ] T030 Create `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260601_001__voucher_identity_backfill_log.sql` per `data-model.md` § new tables. PK `table_name`; `last_hashed_pk`, `rows_hashed`, `started_at`, `completed_at`, `version`.
- [ ] T031 Create `V20260601_002__voucher_quote_purge_log.sql` per `data-model.md`. PK `purge_id UUID`; `purged_at`, `retention_cutoff`, `rows_purged`, `aud_rows_purged`, `duration_ms`. INDEX on `purged_at`.
- [ ] T032 Create `V20260601_003__grafana_ro_role.sql` per `data-model.md` § new database role. `CREATE ROLE cashu_mint_grafana_ro WITH LOGIN PASSWORD :grafana_ro_password`. Column-level `GRANT SELECT (...)` excluding `customer_id` + `merchant_id` on every voucher table.
- [ ] T033 [P] Create `V20260601_004__envers_aud_identity_verify.sql` — pre-flight assertion via `DO $$ BEGIN IF NOT EXISTS (...) THEN RAISE EXCEPTION ...` that every `_aud` table has matching `customer_id`/`merchant_id` columns. Pure verification, no DDL.
- [ ] T034 [P] Create `V20260601_005__drop_unused_columns.sql` per `data-model.md` (research R5a + R5c). `ALTER TABLE merchant_debit_funding DROP COLUMN merchant_ledger_balance_after`; `ALTER TABLE voucher_issuance DROP COLUMN issuance_id`.
- [ ] T035 Create `V20260601_006__hash_iou_terms.sql` per `data-model.md` (research R5b). `ALTER TABLE merchant_iou_funding ADD COLUMN iou_terms_hash CHAR(64)`. Backfill `UPDATE merchant_iou_funding SET iou_terms_hash = encode(sha256(iou_terms::bytea), 'hex')`. Note: `iou_terms` TEXT column dropped in a follow-up migration once backfill verified — leave deprecation comment in place.

### 2D. JPA entities

- [ ] T040 Create `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/entity/VoucherIdentityBackfillLogEntity.java` mapping V20260601_001. `@Entity @Table(name = "voucher_identity_backfill_log")`, `@Id String tableName`, fields per data-model.
- [ ] T041 [P] Create `VoucherQuotePurgeLogEntity.java` mapping V20260601_002.
- [ ] T042 [P] Create Spring Data repositories `VoucherIdentityBackfillLogRepository.java` + `VoucherQuotePurgeLogRepository.java` in `cashu-mint-jpa/src/main/java/.../jpa/repository/`.

### 2E. Anonymous-input safety in existing entities (FR-019 prereq)

- [ ] T050 Modify `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/entity/VoucherQuoteEntity.java` — drop `NOT NULL` constraint on `customer_id` mapping; ensure column annotation matches `data-model.md`. (DB column was already nullable per spec 003 migration; this only adjusts the Java annotation if previously asserted otherwise.)
- [ ] T051 [P] Same for `CustomerPaymentFundingEntity.java`, `MerchantDebitFundingEntity.java`, `MerchantIouFundingEntity.java` per `data-model.md` schema deltas.

**Checkpoint**: All foundational pieces in place. HmacSha256IdentityHasher is wired and fail-closed; backfill + purge tables exist; Grafana role exists; existing entities accept null identity inputs. US1-US6 can now proceed.

---

## Phase 3: User Story 1 — Customer disclosure document (Priority: P1) 🎯 MVP

**Goal**: A privacy-conscious customer can read a published document that lists every identity-bearing column the mint stores per voucher purchase, the retention period, the hashing scheme, and the access controls.

**Independent Test**: `docs/explanations/voucher-data-record.md` exists, is linked from `cashu-mint-rest/README.md`, and the CI smoke test `DisclosureDocSchemaContractTest` confirms every disclosed column matches an actual schema column.

### Implementation for User Story 1

- [ ] T100 [US1] Author `docs/explanations/voucher-data-record.md` per FR-001. Sections: "What we record" (per-column table mirroring `data-model.md` § Retention Scope § A-G), "How it's protected" (HMAC-SHA-256 + salt), "How long it's kept" (90 days for identity; financial state forever), "Who can read it" (operator role mapping), "How to request your data" (FR-009 forensic endpoint procedure).
- [ ] T101 [P] [US1] Update `cashu-mint-rest/README.md` § "Voucher endpoints" — add a "Customer data disclosure" subsection linking to `docs/explanations/voucher-data-record.md` and noting that any deploy MUST link this from the customer-facing UI per FR-008.
- [ ] T102 [P] [US1] Cross-repo follow-up note in `cashu-mint-rest/README.md` — link the `imani-apps` voucher purchase page to the disclosure doc (FR-008; tracked separately in `imani-apps`).
- [ ] T103 [US1] Add CI test `cashu-mint-rest-it/src/test/java/xyz/tcheeric/cashu/mint/rest/spec004/DisclosureDocSchemaContractTest.java` (SC-003) — parses the disclosure markdown table, extracts column names, diffs against JPA `@Column(name=...)` annotations across the voucher entities; fails on drift.

**Checkpoint**: US1 testable in isolation. Customer disclosure published + drift-protected.

---

## Phase 4: User Story 2 — Hash identity at rest (Priority: P1)

**Goal**: Every identity-bearing column stores `HMAC-SHA-256(salt, value)` rather than raw plaintext. A read-only DB dump yields no enumerable customer identifiers.

**Independent Test**: `IdentityHashAtRestIT` creates a voucher quote with a known npub, queries the live `voucher_quote.customer_id` via raw SQL, asserts it's a 64-char hex string matching `HMAC-SHA-256(salt, npub)`. Same for the `_aud` shadow. Anonymous variant (null npub) asserts null storage.

### Implementation for User Story 2

- [ ] T200 [US2] Implement `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/crypto/IdentityHashConverter.java` — `@Converter` that calls `IdentityHasher.hash()` on `convertToDatabaseColumn` and is identity on `convertToEntityAttribute` (we never reverse-resolve the hash). Auto-apply via `@Convert(converter = IdentityHashConverter.class)` on each identity column.
- [ ] T201 [US2] Apply `@Convert(converter = IdentityHashConverter.class)` on `VoucherQuoteEntity.customerId` + `merchantId`. Verify the converter sees null/empty short-circuit from the hasher (no "hash of empty string" persisted).
- [ ] T202 [P] [US2] Same for `CustomerPaymentFundingEntity.customerId`.
- [ ] T203 [P] [US2] Same for `MerchantDebitFundingEntity.merchantId`.
- [ ] T204 [P] [US2] Same for `MerchantIouFundingEntity.merchantId`.
- [ ] T205 [US2] Implement `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/service/VoucherIdentityBackfillService.java` per `quickstart.md` § 3 + `data-model.md` § Flyway migrations gating. `@PostConstruct` invokes a paginated UPDATE loop (1000 rows/txn, configurable via `cashu.mint.voucher.identity-backfill-batch-size`); idempotent via `WHERE customer_id NOT SIMILAR TO '[0-9a-f]{64}'`. Persists progress to `voucher_identity_backfill_log`. Updates live + `_aud` rows in the same transaction (research R9). Marks `completed_at` per table when done. Logs structured event per batch + summary.
- [ ] T206 [US2] Add readiness-probe gate: `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/health/VoucherBackfillHealthIndicator.java` (Spring Actuator `HealthIndicator`). Returns `DOWN` until every entry in `voucher_identity_backfill_log` has `completed_at IS NOT NULL`.
- [ ] T207 [US2] Final migration `V20260601_007__retype_identity_columns.sql` — `ALTER COLUMN ... TYPE CHAR(64)` on each identity column on live + `_aud` tables. Runs LAST; gated on `voucher_identity_backfill_log.completed_at IS NOT NULL` for every applicable table (assertion in migration as `DO $$ BEGIN IF ...`).

### Tests for User Story 2

- [ ] T210 [US2] `cashu-mint-rest-it/src/test/java/xyz/tcheeric/cashu/mint/rest/spec004/IdentityHashAtRestIT.java` (SC-001) — extends `AbstractVoucherDurableIT`; creates a voucher quote with a known customer npub, completes the funding + issuance flow, queries raw SQL `SELECT customer_id FROM voucher_quote WHERE quote_id=?`, asserts the value matches `HmacSha256IdentityHasher.hash(npub)`. Repeats for each of the 4 voucher tables + their `_aud` shadows.
- [ ] T211 [P] [US2] `BackfillResumeIT.java` (FR-011 crash-resume) — seeds 5000 raw-npub rows, starts the backfill, kills it mid-batch by closing the Postgres connection, restarts the Spring context, asserts the backfill resumes from `voucher_identity_backfill_log.last_hashed_pk` and completes without re-hashing already-hashed rows.
- [ ] T212 [P] [US2] `AnonymousPurchaseIT.java` (SC-011 / FR-019) — completes a voucher purchase with NO `customer_id` header, asserts `voucher_quote.customer_id IS NULL` AND `customer_payment_funding.customer_id IS NULL` AND the issuance flow succeeded end-to-end.

**Checkpoint**: US1 + US2 pass. No raw npub remains in voucher tables; anonymous purchases produce zero identity storage.

---

## Phase 5: User Story 3 — Retention window purge (Priority: P2)

**Goal**: Identity columns decay to NULL after 90 days post-terminal-state. Financial fields retained indefinitely.

**Independent Test**: `RetentionPurgeIT` seeds an `ISSUED` voucher quote with `updated_at = now() - 91 days`, runs the purge service synchronously, asserts `customer_id IS NULL AND merchant_id IS NULL` on the live row AND on every `_aud` revision; asserts financial fields (face_value, charged_amount, fee, unit, funding_id, lifecycle_state) are unchanged.

### Implementation for User Story 3

- [ ] T300 [US3] Implement `cashu-mint-jpa/src/main/java/xyz/tcheeric/cashu/mint/jpa/service/VoucherIdentityRetentionPurgeService.java`. `@Scheduled(cron = "${cashu.mint.voucher.identity-purge-cron:0 0 3 * * *}")` daily. For each voucher table: `UPDATE ... SET customer_id = NULL, merchant_id = NULL WHERE lifecycle_state IN ('ISSUED','EXPIRED','FAILED') AND updated_at < (now() - cashu.mint.voucher.identity-retention) AND (customer_id IS NOT NULL OR merchant_id IS NOT NULL)`. Same for `_aud` shadows (research R9). Wraps in one transaction. Records summary in `voucher_quote_purge_log`. Logs structured event.
- [ ] T301 [P] [US3] Idempotency: the SQL `AND (customer_id IS NOT NULL OR merchant_id IS NOT NULL)` clause means re-running is a no-op. Add unit test `VoucherIdentityRetentionPurgeServiceTest.java` asserting `rows_purged == 0` on second run after the first cleared the eligible set.

### Tests for User Story 3

- [ ] T310 [P] [US3] `RetentionPurgeIT.java` (SC-002) — full happy path described above. Two variants: (a) ISSUED row past retention → identity nullified; (b) ISSUED row within retention → unchanged.
- [ ] T311 [P] [US3] `RetentionPurgeAuditTrailIT.java` (FR-010) — after purge, query `voucher_quote_purge_log`; assert a row exists with `purged_at ≈ now()` and `rows_purged > 0`. Verify a separate query distinguishes "anonymous" (null + no matching purge_log) from "purged" (null + matching purge_log entry).
- [ ] T312 [P] [US3] `RetentionPreservesFinancialFieldsIT.java` (SC-005 — funding gate not regressed) — after purge, run the spec-003 SC-001 reconciliation query against the purged data; asserts 0 orphan voucher quotes.

**Checkpoint**: US1 + US2 + US3 pass. Retention boundary enforced; financial reconciliation still works on post-purge data.

---

## Phase 6: User Story 4 — Idempotency cache scrubs identity (Priority: P2)

**Goal**: `voucher_idempotency_key.response_body_json` contains zero raw npubs.

**Independent Test**: `IdempotencyCacheScrubbedIT` triggers a successful voucher quote creation with a known customer npub in the response body, queries `voucher_idempotency_key.response_body_json` via raw SQL, asserts the raw npub does NOT appear and the hashed form (or a redacted placeholder) does.

### Implementation for User Story 4

- [ ] T400 [US4] Modify `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/voucher/VoucherIdempotencyKeyFilter.java` — before persisting to the DB cache, walk the response JSON looking for fields named `customerId` / `customer_id` / `merchantId` / `merchant_id` and replace their values with `IdentityHasher.hash(rawValue)`. Use Jackson `ObjectNode` walk; preserve non-identity fields verbatim.
- [ ] T401 [P] [US4] Alternative if T400's JSON-walk approach is too invasive — implement a Jackson `@JsonSerialize(using = HashIfIdentityFieldSerializer.class)` annotation on the response DTOs. Pick one path in research follow-up; T400 is the default.

### Tests for User Story 4

- [ ] T410 [P] [US4] `IdempotencyCacheScrubbedIT.java` (SC-004) — described above. Asserts via `SELECT response_body_json FROM voucher_idempotency_key WHERE ...` and `assertThat(json).doesNotContain(rawNpub)`.
- [ ] T411 [P] [US4] `IdempotencyReplayServesScrubbedResponseIT.java` — second request with same Idempotency-Key gets back the scrubbed (hashed) response, not the original raw response. Documents the trade-off: replays don't see raw identity even from their own first request.

**Checkpoint**: US4 passes. Idempotency cache contains zero raw npubs.

---

## Phase 7: User Story 5 — Operator forensics still work (Priority: P2)

**Goal**: Operator with salt + raw npub can look up in-window purchases via REST without hand-computing the hash.

**Independent Test**: `OperatorForensicLookupIT` — POST `/admin/voucher/forensic/customer-purchases` with `{"customerNpub": "npub1..."}` returns the matching voucher quotes; same query with a npub that never purchased returns empty; query for a purchase past retention returns the row but with `customer_id IS NULL`.

### Implementation for User Story 5

- [ ] T500 [US5] Implement `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/admin/VoucherForensicController.java` per research R10. `@RestController @RequestMapping("/admin/voucher/forensic")`. Endpoints: `POST /customer-purchases` (body `{customerNpub: string}`) and `POST /merchant-purchases` (body `{merchantNpub: string}`). Both inject `IdentityHasher`, compute the hash internally, query the matching voucher table, return `List<VoucherQuoteSummaryResponse>`.
- [ ] T501 [P] [US5] `SecurityConfig.java` — extend the existing `/admin/**` `hasRole("ADMIN")` rule to cover `/admin/voucher/forensic/**` (likely already covered by the prefix; verify).
- [ ] T502 [P] [US5] `VoucherForensicController` Javadoc — explicit note: "the salt stays inside the mint; operators never see it. This endpoint is the operator-facing path for FR-009."

### Tests for User Story 5

- [ ] T510 [P] [US5] `OperatorForensicLookupIT.java` — three scenarios as described above. Uses HTTP Basic auth with the `admin-it` test credentials (same pattern as `MeltSagaAdminIT`).
- [ ] T511 [P] [US5] `OperatorForensicUnauthIT.java` — request without credentials returns 401; wrong role returns 401 (no other role mapped today).
- [ ] T512 [P] [US5] `OperatorForensicPostRetentionIT.java` — seeds a row past retention (post-purge), asserts the query returns the matching `quote_id` + amounts but `customer_id` field in the response is null + a `retention_state` field indicates `purged`.

**Checkpoint**: US5 passes. Operators can do customer lookups without breaking the privacy model.

---

## Phase 8: User Story 6 — Grafana operator dashboards (Priority: P2)

**Goal**: Retained financial data exposed via three Grafana dashboards. Aggregate panels reference zero identity columns; per-record panels render truncated hashes / `{purged}` sentinel.

**Independent Test**: dashboards exist, queries respect the column boundary, the orphan-issuance synthetic injection fires the alert within 5 minutes.

### Implementation for User Story 6

- [ ] T600 [US6] Add Grafana data source provisioning `cashu-mint-observability/docker/grafana/provisioning/datasources/voucher-postgresql.yaml`. Connect as `cashu_mint_grafana_ro` reading `GRAFANA_RO_PASSWORD` from env.
- [ ] T601 [US6] Author `cashu-mint-observability/docker/grafana/dashboards/voucher-liability-overview.json` per `contracts/grafana-panels.md` § Dashboard 1. Four panels, all PostgreSQL.
- [ ] T602 [P] [US6] Author `voucher-token-integrity.json` per Dashboard 2. Four panels + Alertmanager rule for orphan_issued_vouchers > 0.
- [ ] T603 [P] [US6] Author `voucher-iou-liability.json` per Dashboard 3. Three panels including the field-override formatter for truncated-hash + `{purged}` rendering.
- [ ] T604 [P] [US6] Modify `voucher-business.json` (existing) per `contracts/grafana-panels.md` § Dashboard 4 — rename plural `vouchers_*` metric refs to singular `voucher_*`.
- [ ] T605 [P] [US6] Add Prometheus relabel rules `cashu-mint-observability/docker/prometheus/prometheus.yml` aliasing old plural names to new singular names for one release cycle (FR-017). Document the deprecation window in the comment.
- [ ] T606 [US6] Add Alertmanager rule `cashu-mint-observability/docker/alertmanager/voucher-rules.yml` — routes `voucher_orphan_issuance` to `PAGERDUTY_SERVICE_KEY`; `voucher_stuck_quote` + `voucher_funding_required_high` to `SLACK_WEBHOOK_VOUCHERS_OPS`; `voucher_overdue_iou` + `voucher_policy_drift` to `SLACK_WEBHOOK_MERCHANT_FINANCE`.

### Tests for User Story 6

- [ ] T610 [P] [US6] `GrafanaRolePermissionIT.java` (SC-012) — connects as `cashu_mint_grafana_ro` via JDBC, attempts `SELECT customer_id FROM voucher_quote LIMIT 1`, asserts `PSQLException` with state `42501`. Same for `merchant_id` across the 4 voucher tables.
- [ ] T611 [P] [US6] `DashboardCoverageContractTest.java` (SC-007) — parses every panel JSON in the 3 new dashboards, extracts column / metric identifiers, diffs against the Retention Scope § A-G retained-field list (data-model.md). Fails if a retained field has no panel OR if a panel references a non-retained column.
- [ ] T612 [P] [US6] `DashboardPrivacyContractTest.java` (SC-008) — parses every per-record panel JSON, asserts no panel selects an identity column without a display formatter / mapping that renders truncated hash or `{purged}`.
- [ ] T613 [US6] `IntegrityAlertSyntheticIT.java` (FR-018 / SC-009) — boots a mint with a captured Alertmanager webhook receiver; seeds an orphan ISSUED voucher_quote row; polls the webhook receiver for the `voucher_orphan_issuance` alert; asserts it fires within 5 minutes.
- [ ] T614 [P] [US6] `MetricNamingLintTest.java` (SC-010) — lints every dashboard JSON for the deprecated plural `vouchers_*` substring; asserts count = 0 after the rename in T604.

**Checkpoint**: All six user stories pass independently.

---

## Phase 9: Polish & Cross-Cutting

- [ ] T900 [P] Run `mvn -q verify -P integration-tests`; attach the IT report. Pay special attention to `IntegrityAlertSyntheticIT` (timer-flake risk) and `BackfillResumeIT` (connection-kill flake risk).
- [ ] T901 [P] Update `CLAUDE.md` § "Spec 004 — Voucher Data Minimisation" subsection: add architecture summary, link to spec.md + research.md, document the salt environment variable, mention the `cashu_mint_grafana_ro` role.
- [ ] T902 [P] Update `cashu-mint-rest/README.md` § "Voucher endpoints" with the new admin forensic endpoints (T500), the salt env var (T021), and a link to `quickstart.md`.
- [ ] T903 Update operator runbook `docs/runbooks/voucher-data-minimisation.md` (new) — symlink / cross-reference `quickstart.md` for the day-to-day operator path; add a top-of-file pointer from `docs/runbooks/virtual-thread-issues.md` so operators can discover it.
- [ ] T904 Schema lint job: add `cashu-mint-jpa/src/test/java/xyz/tcheeric/cashu/mint/jpa/schema/IdentityColumnSchemaLintTest.java` — parses Flyway migrations + entity classes; fails if a new identity-named column lands without the `@Convert(IdentityHashConverter.class)` annotation. Prevents regression where a future PR adds `merchant_email` and forgets to hash it.
- [ ] T905 Manual smoke against staging per `quickstart.md` § 4: generate a salt; deploy; create a customer-paid voucher; verify `SELECT customer_id FROM voucher_quote` returns the hash; verify SC-001 reconciliation returns 0; verify the Liability dashboard renders.
- [ ] T906 [P] Cross-repo follow-up notes: open tracking issues in `imani-gateway-atomic` (adopt the same HMAC scheme + shared salt per research R2) and `imani-apps` (link the disclosure doc per FR-008 per research R3). Document the issue URLs in `cashu-mint-rest/README.md`.
- [ ] T907 Sync Impact Report: amend `.specify/memory/constitution.md` with the 1.2.0 ratification entry summarising the principle's addition + a pointer to spec 004 as the demonstrated coexistence pattern.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** (Setup): depends on PR #321 + #322 merged. No internal blockers.
- **Phase 2** (Foundational): depends on Phase 1. Internal order:
  - 2A constitution ratification: independent
  - 2B IdentityHasher port + impl: independent
  - 2C migrations: T030 → T031 → T032 → T033 → T034 → T035 (sequential per Flyway version order)
  - 2D entities: depends on 2C migrations being applied
  - 2E entity nullability: depends on 2D
- **Phase 3 (US1)**: depends on Phase 2 (doc references hashing scheme + retention from Phase 2 docs)
- **Phase 4 (US2)**: depends on Phase 2 (uses the IdentityHasher port + the new tables)
- **Phase 5 (US3)**: depends on Phase 4 (purge only meaningful when identity is hashed first)
- **Phase 6 (US4)**: depends on Phase 4 (idempotency scrub uses IdentityHasher)
- **Phase 7 (US5)**: depends on Phase 4 (forensic CLI uses IdentityHasher to compute the lookup hash)
- **Phase 8 (US6)**: depends on Phase 2 (Grafana role) + Phase 5 (purge log informs `{purged}` display)
- **Phase 9 (Polish)**: depends on Phase 8 finishing

### Within each user story

- Implementation tasks before tests within the same story (mint follows TDD-light: implement → integration test against real Postgres).
- Migrations before entities; entities before services; services before controllers; controllers before ITs.

### Parallel opportunities

- All `[P]` tasks within a phase parallel.
- US2 + US3 + US4 + US5 implementation tasks can be parallelised by 3-4 developers once Phase 2 lands (each story owns its own files; no overlap).
- US6 Grafana dashboard JSON authoring is heavily parallel — three independent files.

---

## Implementation Strategy

### MVP (US1 + US2 — disclosure + hash at rest)

The minimum spec-004 deployment that delivers value:

1. Phase 1 → Phase 2 (foundational) → US1 (disclosure) → US2 (hash at rest + backfill)
2. Deploy to staging; verify SC-001 reconciliation still green; verify hash-at-rest via raw SQL spot check
3. **Important**: until US3 lands, retention purge doesn't run — existing rows age into hashed-but-not-purged state. Document this in the staging deploy notes.

### Incremental rollout

1. MVP (US1 + US2) → deploy → observe Grafana for backfill completion + SC-001 stability
2. Add US3 (retention purge) → deploy → observe daily purge_log entries
3. Add US4 (idempotency scrub) → deploy
4. Add US5 (operator forensic CLI) → deploy → train ops team via quickstart.md
5. Add US6 (Grafana dashboards) → deploy → verify alerts fire correctly via T613

### Parallel team

- Dev A: US1 + US2 (the privacy core) + cross-repo coordination (T906)
- Dev B: US3 + US4 (purge + scrub)
- Dev C: US5 + US6 (operator surface — REST + Grafana)
- Joint: Phase 2 foundational, Phase 9 polish

---

## Notes

- The spec-003 funding gate (PR #321 FR-002) is the load-bearing token-integrity invariant. Every spec-004 task that touches the voucher path MUST preserve it; SC-005 + T312 explicitly assert no regression.
- The Grafana DB role uses column-level `GRANT SELECT` — this is defence in depth on top of dashboard JSON review. If T611 / T612 / T610 ever fail, the failure is structural (the DB layer rejected the query) rather than a missed code review.
- Salt rotation is forbidden in v1 (FR-007). The quickstart documents the offline emergency procedure but spec-004 doesn't ship rotation automation. A leaked salt is a separate incident response, not a routine operation.
- Cross-repo work (imani-gateway-atomic + imani-apps) is tracked separately. Spec 004 ships the mint-side contract; downstream adoption follows independently. The funding gate fallback in cashu-mint's resolver (PR #321) means correctness holds even if the downstream specs lag.
- This spec ratifies Constitution Principle VII (1.1.0 → 1.2.0). The ratification commit is T010 (Phase 2A); the Sync Impact Report update is T907 (Phase 9).
