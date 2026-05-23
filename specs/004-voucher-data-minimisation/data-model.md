# Phase 1 Data Model: Voucher Data Minimisation and Customer-Identity Custody

**Feature**: 004-voucher-data-minimisation
**Date**: 2026-05-23
**Source**: Phase 0 research (`research.md`), spec.md § Retention Scope

This document captures every schema delta against spec 003's tables. The bulk of the change is column-level: identity columns shift from raw TEXT npubs to 64-char hex HMAC-SHA-256 digests. Two new audit tables (`voucher_identity_backfill_log`, `voucher_quote_purge_log`) capture lifecycle events. One new database role (`cashu_mint_grafana_ro`) gates Grafana queries at the PostgreSQL layer.

---

## Schema deltas on existing spec-003 tables

### `voucher_quote` (Envers-audited)

| Column | Spec 003 type | Spec 004 type | Change |
|---|---|---|---|
| `customer_id` | `VARCHAR(255)` NULLABLE | `CHAR(64)` NULLABLE | Stores `HMAC-SHA-256(salt, raw_npub)` hex. Null = anonymous (FR-019) OR purged (FR-003). |
| `merchant_id` | `VARCHAR(255)` NULLABLE | `CHAR(64)` NULLABLE | Same hashing scheme. |
| (all other columns) | unchanged | unchanged | Retained per Retention Scope § A–G. |

Migration `V20260601_006__hash_identity_columns_voucher_quote.sql` runs `ALTER TABLE voucher_quote ALTER COLUMN customer_id TYPE CHAR(64), ALTER COLUMN merchant_id TYPE CHAR(64)` — but ONLY after the backfill job (FR-011) has converted all existing values. To make the migration safe:

1. Backfill runs as `@PostConstruct` (FR-011) — hashes every non-null raw npub in-place, in 1000-row chunks. Idempotent re-hash means crash-and-resume is safe.
2. Backfill writes a `voucher_identity_backfill_log` row marking the table complete.
3. The `ALTER TABLE ... TYPE CHAR(64)` migration runs LAST, gated on the backfill log showing complete. PostgreSQL accepts the cast because every value is already a 64-char hex string.

### `voucher_quote_aud` (Envers shadow)

Same column changes as `voucher_quote`. The backfill job updates both live and `_aud` rows in the same transaction (research R9) — deliberate Envers immutability break for identity columns only; financial columns remain immutable in the shadow.

### `customer_payment_funding`

| Column | Spec 003 type | Spec 004 type | Change |
|---|---|---|---|
| `customer_id` | `VARCHAR(255)` NULLABLE | `CHAR(64)` NULLABLE | Same hashing as `voucher_quote.customer_id`. |
| (all other columns) | unchanged | unchanged | `provider_event_id`, `provider`, `webhook_event_quote_id` retained — see Retention Scope § B. |

### `merchant_debit_funding`

| Column | Spec 003 type | Spec 004 type | Change |
|---|---|---|---|
| `merchant_id` | `VARCHAR(255)` NOT NULL | `CHAR(64)` NOT NULL | Same hashing scheme. |
| `merchant_debit_id` | `VARCHAR(255)` NOT NULL | unchanged | External merchant-ledger anchor; NOT identity (Retention Scope § C). |
| `merchant_ledger_balance_after` | `BIGINT` NULLABLE | **DROPPED** | Research R5a — unused forensic snapshot. |

### `merchant_iou_funding`

| Column | Spec 003 type | Spec 004 type | Change |
|---|---|---|---|
| `merchant_id` | `VARCHAR(255)` NOT NULL | `CHAR(64)` NOT NULL | Same hashing scheme. |
| `iou_terms` | `TEXT` NULLABLE | **RENAMED + RETYPED** to `iou_terms_hash CHAR(64) NULLABLE` | Research R5b — store SHA-256 content hash of the terms document, not the verbatim text. Migration hashes existing values + drops the old column. |
| `iou_id`, `iou_due_at`, `policy_profile` | unchanged | unchanged | Retained — see Retention Scope § D. |

### `voucher_issuance`

| Column | Spec 003 type | Spec 004 type | Change |
|---|---|---|---|
| `issuance_id` | `VARCHAR(64)` NOT NULL | **DROPPED** | Research R5c — denormalised mirror with no consumer. Migration drops the column; audit query Javadoc on `VoucherIssuanceJpaRepository` updated. |
| (all other columns) | unchanged | unchanged | |

### `voucher_idempotency_key.response_body_json`

No schema change; FR-005 specifies a **runtime scrub** in `VoucherIdempotencyKeyFilter` so cached response bodies do not contain raw identity. The cache table type stays `TEXT`; the cleaning happens at write time before the row is persisted.

---

## New tables

### `voucher_identity_backfill_log`

Append-only progress tracker for the FR-011 backfill job. Lets operators verify completion and resume after a crash.

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `table_name` | `VARCHAR(64)` | PK, NOT NULL | One row per voucher table (`voucher_quote`, `customer_payment_funding`, `merchant_debit_funding`, `merchant_iou_funding`, + `_aud` variants) |
| `last_hashed_pk` | `VARCHAR(64)` | NULLABLE | The highest primary key whose row has been hashed. NULL = job not started for this table |
| `rows_hashed` | `BIGINT` | NOT NULL, default 0 | Running count for observability |
| `started_at` | `TIMESTAMPTZ` | NULLABLE | First batch start |
| `completed_at` | `TIMESTAMPTZ` | NULLABLE | Final batch commit; presence = "this table is done"; SC-001 lint job reads this |
| `version` | `BIGINT` | NOT NULL, default 0 | JPA `@Version`; serialises concurrent backfill attempts |

**Indexes**: PK on `table_name`.

**Not Envers-audited**: by definition append-only; no value in shadowing.

### `voucher_quote_purge_log`

Append-only audit log of retention purge runs (FR-010).

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `purge_id` | `UUID` | PK, NOT NULL | Generated per purge run |
| `purged_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | When the purge ran |
| `retention_cutoff` | `TIMESTAMPTZ` | NOT NULL | The `updated_at < cutoff` threshold the purge applied — derived from `cashu.mint.voucher.identity-retention` at run time |
| `rows_purged` | `BIGINT` | NOT NULL | Count of `voucher_quote` rows whose identity columns were nullified |
| `aud_rows_purged` | `BIGINT` | NOT NULL | Count of `_aud` rows whose identity columns were nullified |
| `duration_ms` | `BIGINT` | NOT NULL | Wall-clock duration |

**Indexes**: PK on `purge_id`; INDEX on `purged_at` for "show me purge history" queries.

This is the FR-010 "purged on date X" marker — when an operator queries a row whose identity is NULL, they can correlate to a purge_log row to distinguish "anonymous purchase" (no matching purge_log entry; `created_at` recent) from "post-retention purge" (matching purge_log entry; `created_at` past retention).

---

## New database role

### `cashu_mint_grafana_ro`

Created by Flyway migration `V20260601_003__grafana_ro_role.sql`. Grafana's PostgreSQL data source connects as this role.

```sql
CREATE ROLE cashu_mint_grafana_ro WITH LOGIN PASSWORD :grafana_ro_password;

-- voucher_quote: financial + state only; NO customer_id or merchant_id
GRANT SELECT (quote_id, voucher_type, face_value, charged_amount, fee, unit,
              funding_id, lifecycle_state, idempotency_key, request_hash,
              created_at, updated_at, version)
  ON voucher_quote TO cashu_mint_grafana_ro;

-- voucher_funding parent: financial + discriminator only
GRANT SELECT (funding_id, funding_source, amount, unit, created_at, version)
  ON voucher_funding TO cashu_mint_grafana_ro;

-- customer_payment_funding: provider anchors only; NO customer_id
GRANT SELECT (funding_id, provider, provider_event_id, webhook_event_quote_id)
  ON customer_payment_funding TO cashu_mint_grafana_ro;

-- merchant_debit_funding: ledger anchor only; NO merchant_id
GRANT SELECT (funding_id, merchant_debit_id)
  ON merchant_debit_funding TO cashu_mint_grafana_ro;

-- merchant_iou_funding: IOU instrument only; NO merchant_id
GRANT SELECT (funding_id, iou_id, iou_terms_hash, iou_due_at, policy_profile)
  ON merchant_iou_funding TO cashu_mint_grafana_ro;

-- voucher_issuance: full read OK (no identity columns by design)
GRANT SELECT ON voucher_issuance TO cashu_mint_grafana_ro;

-- Audit tables: NO grants (sensitive history)
-- _aud tables: NO grants (sensitive history)
```

**The Grafana dashboards described in spec.md FR-014/FR-015/FR-016 use ONLY columns granted above.** A future panel author who writes `SELECT customer_id FROM voucher_quote` gets `permission denied for column customer_id` at the PostgreSQL layer (SC-012).

---

## State transition invariants (additions to spec 003's set)

For every `voucher_quote` row, in addition to spec-003 lifecycle invariants:

- **At creation (FR-019)**: `customer_id` MAY be null (anonymous purchase) OR is the HMAC of a customer-supplied raw npub. `merchant_id` is set when the funding source is `MERCHANT_DEBIT` or `MERCHANT_IOU`.
- **Post-retention (FR-003)**: when `lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')` AND `updated_at < now() - retention`, both `customer_id` AND `merchant_id` MUST be null in the live row AND every `_aud` revision.
- **Anonymous vs purged distinction (FR-019 + FR-010)**: `customer_id IS NULL AND no matching voucher_quote_purge_log row covering this quote's updated_at` ⇒ anonymous. `customer_id IS NULL AND matching purge_log row` ⇒ purged.

For every `voucher_identity_backfill_log` row: `completed_at IS NOT NULL` ⇒ no plaintext npub remains in `(table_name)`. The startup sequence MUST NOT serve voucher endpoints until every entry under `(voucher_quote, customer_payment_funding, merchant_debit_funding, merchant_iou_funding, voucher_quote_aud, customer_payment_funding_aud, merchant_debit_funding_aud, merchant_iou_funding_aud)` has `completed_at IS NOT NULL`.

---

## Flyway migrations (in order)

`cashu-mint-jpa/src/main/resources/db/migration/spec001/`:

1. **`V20260601_001__voucher_identity_backfill_log.sql`** — Creates the backfill tracker table.
2. **`V20260601_002__voucher_quote_purge_log.sql`** — Creates the purge audit log.
3. **`V20260601_003__grafana_ro_role.sql`** — Creates the read-only role + column-level GRANTs. Password comes from env via Flyway placeholder `${grafana_ro_password}`.
4. **`V20260601_004__envers_aud_identity_verify.sql`** — Pre-flight check: every voucher `_aud` table has matching `customer_id` / `merchant_id` columns. Pure verification, no DDL (Envers auto-creates these).
5. **`V20260601_005__drop_unused_columns.sql`** — Drops `merchant_ledger_balance_after`, drops `voucher_issuance.issuance_id` (research R5a + R5c).
6. **`V20260601_006__hash_iou_terms.sql`** — Adds `iou_terms_hash CHAR(64) NULLABLE`. Backfill job populates from `iou_terms`. The old TEXT column is dropped in a later migration once backfill confirms completion.
7. **`V20260601_007__retype_identity_columns.sql`** — `ALTER COLUMN ... TYPE CHAR(64)` on identity columns. Runs LAST, only after backfill log shows complete. PostgreSQL accepts cast because every value is now a 64-char hex.

---

## Out of Scope

- Schema for the per-customer opt-out audit (Clarifications Q3 makes the column nullable, no separate audit row needed)
- Cross-repo schema sync with `imani-gateway-atomic` — separate spec
- Multi-replica salt distribution (Open Item closed via the constitution Principle VII assumption of single-replica today)
- Indexes for the salt-aware forensic lookup (FR-009) — pure point-query on `customer_id` HMAC, the existing PK / index suffices
