# Implementation Plan: Voucher Data Minimisation and Customer-Identity Custody

**Branch**: `004-voucher-data-minimisation` (to be created from `003-voucher-its` once #321 + #322 merge) | **Date**: 2026-05-23 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-voucher-data-minimisation/spec.md`

## Summary

Spec 003 closed the silent-inflation hole by making every voucher proof issuance trace to a durable funding row. That fix is sound for **token** non-custodiality but introduced a new kind of custody: the mint now holds, durably and with Envers revision history, the tuple `(customer_npub, merchant_npub, amount, unit, timestamp)` per voucher purchase. Spec 004 closes the data-custody gap **without weakening the spec-003 funding gate**.

Technical approach: store every identity-bearing column as `HMAC-SHA-256(key=mint_salt, msg=value)` (Clarifications Q1) so a DB dump yields no enumerable customer identifiers; nullify identity columns on `voucher_quote` + funding child tables + their Envers `_aud` shadows after a 90-day retention window (Q2); allow anonymous purchases by making `customer_id` nullable at creation with a null short-circuit on the hash function (Q3); add a `cashu_mint_grafana_ro` PostgreSQL role with column-level `GRANT SELECT` excluding identity columns so an ad-hoc Grafana query can never read raw npubs even if the dashboard JSON review lapses (Q4); migrate existing spec-003 rows via an online batched, idempotent backfill (Q5). Three new Grafana dashboards (Liability, Token Integrity, IOU) expose the retained financial state for operators with privacy-aware display patterns.

The funding gate (FR-002 in spec 003) remains the load-bearing token-integrity invariant. Any data-minimisation measure that would risk re-opening the silent-inflation hole is rejected; Token Integrity (Constitution I) wins on conflict.

## Technical Context

**Language/Version**: Java 21 (matches spec 001/002/003)
**Primary Dependencies**:
- Spring Boot 3.5.10 (existing)
- Hibernate JPA + Hibernate Envers (existing — `_aud` shadow tables already in place from spec 003)
- Flyway 11.x (existing — new migrations under `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260601_*.sql`)
- `javax.crypto.Mac` for HMAC-SHA-256 (JDK-native, no third-party crypto dep)
- Spring `@Scheduled` for the retention purge job (same pattern as `VoucherIdempotencyKeySweeper` from spec 003)
- Spring `@PostConstruct` + `JdbcTemplate` for the backfill job
- Caffeine cache for the `MintIdentitySalt` in-memory cache (no DB persistence)
- Grafana 10.x with PostgreSQL data source plugin (already shipped via `cashu-mint-observability/docker/docker-compose.observability.yml`)

**Storage**: PostgreSQL 16 — no new tables introduced for identity hashing; the existing voucher tables get column updates. Two new tables added: `voucher_identity_backfill_log` (FR-011 progress tracker) and `voucher_quote_purge_log` (FR-010 purged-revision marker). Both append-only.

**Testing**:
- Unit (JUnit 5): hash function (FR-002, null handling), retention purge job (FR-003, idempotency)
- Integration (Testcontainers PostgreSQL): SC-001 lint, SC-002 retention enforcement, SC-011 anonymous purchase end-to-end, SC-012 Grafana role permission denial, FR-011 backfill resume after crash
- Schema regression: SnakeYAML-based parse of dashboard JSON (SC-007 retained-field-to-panel coverage + SC-008 truncated-hash assertion)
- Synthetic alert IT (FR-018): inject orphan voucher → assert PagerDuty webhook fires within 5min via captured notifier mock

**Target Platform**: Linux server (Docker), JVM 21. Same as spec 003.

**Project Type**: Multi-module Maven service (extends spec 003's `cashu-mint-jpa` + `cashu-mint-rest` + `cashu-mint-observability` modules).

**Performance Goals**:
- HMAC-SHA-256 per-request overhead ≤ 1ms p99 (FR-012 / SC-006)
- Backfill ≤ 5min for a 10M-row table (FR-012)
- Retention purge job runs daily within a configurable maintenance window; default 2-3 AM mint-local
- No regression on voucher mint p95 latency from spec 003 baseline (Grafana panel watches the trend)

**Constraints**:
- MUST NOT weaken the spec-003 funding gate (FR-002 reconciliation must stay green — SC-005)
- MUST NOT add a third-party crypto dependency — JDK-native primitives only (Clarifications Q1)
- Salt MUST live in environment variables / secret manager; never persisted to DB or logged (FR-002)
- Backfill MUST be online — no table-level lock (Clarifications Q5)
- Grafana DB role MUST NOT be granted any identity column (Clarifications Q4)
- All voucher amounts continue as `long` (Constitution I, inherited from spec 003)

**Scale/Scope**:
- Current row volume: small (staging-scale; well under 1M voucher quotes). The 10M-row backfill ceiling is forward-projection, not current state.
- Single-replica mint deployment. Multi-replica salt distribution is explicitly out of scope for v1 (Open Item #11 noted).
- 3 new Grafana dashboards (Liability, Integrity, IOU) + extension to existing Business dashboard.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution: cashu-mint v1.1.0 (Principle VII drafted alongside this spec; ratified to 1.2.0 when this plan + tasks are accepted).

| Principle | Gate | Status |
|---|---|---|
| I. Token Integrity — no silent inflation | Spec 003 funding gate (FR-002) MUST remain intact; data-minimisation MUST NOT weaken it | ✅ Design preserves it; SC-005 explicitly asserts no regression |
| I. Token Integrity — durable financial state | Financial fields (face_value, charged_amount, fee, unit, funding_id, lifecycle_state, funding rows, issuance ledger) retained indefinitely per Retention Scope § A | ✅ |
| I. Token Integrity — `long` arithmetic | No new amount-bearing fields introduced; existing `long` typing preserved | ✅ Inherited |
| I. Token Integrity — operator-visible alerts | FR-015 orphan-issuance PagerDuty alert; FR-014 IOU issuance counter; FR-016 overdue-IOU Slack | ✅ Designed |
| II. Protocol Compliance | No NUT changes; vouchers remain non-standard vendor extension | ✅ Inherited |
| III. Clean Architecture | Hash function is a port (`IdentityHasher`) in cashu-mint-protocol; HMAC implementation in cashu-mint-jpa; Grafana dashboards in cashu-mint-observability | ✅ Designed |
| IV. Testing Discipline | Unit + Testcontainers IT for hash, retention, backfill, anonymous flow, DB role denial, dashboard JSON shape | ✅ Designed |
| V. Virtual Threads | Retention purge + backfill run on virtual-thread executor (I/O-bound); same idiom as spec 001/002/003 | ✅ Inherited |
| VI. Secure Coding & Code Quality | HMAC primitive correct; salt from env not config file; null short-circuit (no enumerable hash of empty); column-level DB grant | ✅ Designed |
| VII. Data Minimisation (DRAFT) | Disclosure (FR-001); hashed at rest (FR-002); time-bound retention (FR-003); idempotency cache scrubbed (FR-005); operator forensics preserved via salt-aware CLI (FR-009); salt rotation planned operation (FR-007) | ✅ This spec ratifies the principle |

**Result**: PASS. Complexity Tracking intentionally empty (no constitution violations — this spec adds a new principle rather than violating an existing one).

## Project Structure

### Documentation (this feature)

```text
specs/004-voucher-data-minimisation/
├── plan.md              # This file
├── research.md          # Phase 0 — resolves 4 minimisation candidates (Retention Scope § H) + metric naming + 4 open items
├── data-model.md        # Phase 1 — column-by-column hashing scheme + 2 new tables (backfill_log, purge_log) + Grafana role + GRANT statements
├── contracts/
│   ├── identity-hasher.md  # IdentityHasher port + HMAC-SHA-256 implementation contract
│   └── grafana-panels.md   # Per-panel SQL/PromQL contracts + privacy-aware display rules
├── quickstart.md        # Operator runbook: set the salt, run the first backfill, verify SC-001 reconciliation
└── tasks.md             # Phase 2 (NOT created by /speckit.plan — produced by /speckit.tasks)
```

### Source Code (repository root)

```text
cashu-mint-protocol/
└── src/main/java/xyz/tcheeric/cashu/mint/proto/
    ├── ports/
    │   └── IdentityHasher.java                       # NEW — single-method port: String hash(String value)
    └── domain/
        └── (no new types; hashing semantics covered by IdentityHasher contract)

cashu-mint-jpa/
├── src/main/java/xyz/tcheeric/cashu/mint/jpa/
│   ├── crypto/
│   │   └── HmacSha256IdentityHasher.java              # NEW — @Component, JDK Mac.getInstance("HmacSHA256")
│   ├── entity/
│   │   ├── VoucherQuoteEntity.java                    # MODIFY — @Convert(converter = IdentityHashConverter.class) on customer_id/merchant_id
│   │   ├── CustomerPaymentFundingEntity.java          # MODIFY — same on customer_id
│   │   ├── MerchantDebitFundingEntity.java            # MODIFY — same on merchant_id
│   │   ├── MerchantIouFundingEntity.java              # MODIFY — same on merchant_id
│   │   ├── VoucherIdentityBackfillLogEntity.java      # NEW — (table_name, last_hashed_pk, completed_at)
│   │   └── VoucherQuotePurgeLogEntity.java            # NEW — (purged_at, retention_cutoff, rows_purged)
│   ├── service/
│   │   ├── VoucherIdentityBackfillService.java        # NEW — @PostConstruct boot job, 1000-row chunks, idempotent
│   │   └── VoucherIdentityRetentionPurgeService.java  # NEW — @Scheduled daily, nullifies past retention
│   └── repository/
│       ├── VoucherIdentityBackfillLogRepository.java  # NEW
│       └── VoucherQuotePurgeLogRepository.java        # NEW
└── src/main/resources/db/migration/spec001/
    ├── V20260601_001__voucher_identity_backfill_log.sql   # backfill progress tracker
    ├── V20260601_002__voucher_quote_purge_log.sql         # purge audit log
    ├── V20260601_003__grafana_ro_role.sql                 # cashu_mint_grafana_ro role + column-level GRANTs
    └── V20260601_004__envers_aud_identity_columns.sql     # (verifies _aud shadow has matching identity columns; no DDL if Envers already auto-created)

cashu-mint-rest/
└── src/main/java/xyz/tcheeric/cashu/mint/rest/
    ├── voucher/
    │   └── VoucherIdempotencyKeyFilter.java            # MODIFY — response_body_json scrubber (FR-005)
    ├── admin/
    │   └── VoucherForensicController.java              # NEW — operator-facing salt-aware lookup (FR-009)
    └── config/
        └── VoucherDurabilityProperties.java            # MODIFY — add identity-salt, identity-retention, identity-backfill-batch-size

cashu-mint-observability/
└── docker/grafana/
    ├── provisioning/datasources/
    │   └── voucher-postgresql.yaml                     # NEW — Grafana provisions PostgreSQL data source w/ cashu_mint_grafana_ro
    └── dashboards/
        ├── voucher-liability-overview.json             # NEW — FR-014
        ├── voucher-token-integrity.json                # NEW — FR-015 + Alertmanager rule
        ├── voucher-iou-liability.json                  # NEW — FR-016, truncated-hash + {purged} display
        └── cashu-mint-business.json                    # MODIFY — metric naming reconciliation (FR-017)

cashu-mint-rest-it/
└── src/test/java/xyz/tcheeric/cashu/mint/rest/spec004/
    ├── IdentityHashAtRestIT.java                       # SC-001: 100% hashed in live tables
    ├── RetentionPurgeIT.java                           # SC-002: rows past retention have NULL identity
    ├── AnonymousPurchaseIT.java                        # SC-011: anonymous flow zero-identity end-to-end
    ├── GrafanaRolePermissionIT.java                    # SC-012: cashu_mint_grafana_ro can't SELECT identity columns
    ├── BackfillResumeIT.java                           # FR-011: crash mid-batch, restart, completes
    ├── IdempotencyCacheScrubbedIT.java                 # SC-004: no raw npubs in response_body_json
    ├── DashboardCoverageContractTest.java              # SC-007: every retained field surfaces in ≥1 panel
    └── DashboardPrivacyContractTest.java               # SC-008: per-record panels render truncated hash OR {purged}

docs/explanations/
└── voucher-data-record.md                              # NEW — FR-001 customer-facing disclosure

.specify/memory/
└── constitution.md                                     # MODIFY — ratify Principle VII (1.1.0 → 1.2.0)
```

**Structure Decision**: extends spec 003's existing module layout. No new Maven module — the changes spread across `cashu-mint-protocol` (port), `cashu-mint-jpa` (hasher + entities + services + migrations), `cashu-mint-rest` (filter scrub + forensic controller + config), `cashu-mint-observability` (3 dashboards + DB role provisioning), `cashu-mint-rest-it` (8 ITs). One new documentation file under `docs/explanations/` for the FR-001 disclosure. Constitution gets a Principle VII ratification bump.

## Complexity Tracking

> Constitution Check passed. Section intentionally empty.
