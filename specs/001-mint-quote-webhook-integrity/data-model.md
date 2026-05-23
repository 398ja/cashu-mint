# Phase 1 Data Model: Mint Quote Amount Binding and Webhook Integrity

**Feature**: 001-mint-quote-webhook-integrity
**Date**: 2026-05-22
**Source**: Phase 0 research (`research.md`)

Three new entities + three Flyway migrations + one Envers
auto-tracked history. All entities live in the new `cashu-mint-jpa`
module (per `plan.md` Structure Decision). All financial-amount
fields are `long`. All timestamps come from PostgreSQL `now()`.

---

## Entity: MintQuote

Durable record of a NUT-04 quote authorisation. Source of truth for
the lifecycle state machine. Envers audit-tracked.

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `quote_id` | `VARCHAR(64)` | PK, NOT NULL | Provider-issued or mint-issued unique id |
| `amount` | `BIGINT` | NOT NULL, > 0 | Authorised amount in unit's smallest denomination |
| `unit` | `VARCHAR(16)` | NOT NULL | e.g. `sat`, `msat`, `eur`, `usd` |
| `mint_url` | `TEXT` | NOT NULL | URL the quote is issued against |
| `payment_method` | `VARCHAR(32)` | NOT NULL | e.g. `bolt11`, `cash`, `mobile_money` |
| `invoice_id` | `VARCHAR(255)` | NULLABLE | Provider invoice id (Lightning, etc.) |
| `lifecycle_state` | `VARCHAR(16)` | NOT NULL, default `UNPAID` | See state machine below |
| `request_hash` | `CHAR(64)` | NOT NULL | SHA-256 of canonicalised quote request payload (tamper detection) |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | DB clock |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | Updated by trigger or @PreUpdate |
| `version` | `BIGINT` | NOT NULL, default 0 | JPA `@Version` for optimistic locking on non-lifecycle updates |

**Indexes**:
- PRIMARY KEY (`quote_id`)
- INDEX on `lifecycle_state` (for operator dashboards: "how many
  quotes are stuck in `PAID` or `ISSUING`?")
- INDEX on `created_at` (for retention queries; out of scope here
  but cheap to add)

**Envers**: `mint_quote_aud` auto-created. `lifecycle_state`,
`amount`, `unit`, `payment_method`, `invoice_id` audited. Audit
clock comes from `REVINFO.timestamp` populated by Hibernate using
the database clock per Constitution II.

**Lifecycle State Machine**:

```text
            (create quote)
                  │
                  ▼
              ┌────────┐
              │ UNPAID │
              └────┬───┘
                   │ provider webhook received (PENDING per protocol)
                   ▼
              ┌─────────┐
              │ PENDING │
              └────┬────┘
                   │ webhook amount+unit+method+event_id match
                   │ (CAS: PENDING -> PAID)
                   ▼
              ┌─────┐
              │PAID │
              └──┬──┘
                 │ client calls /mint with matching blinded outputs
                 │ (CAS: PAID -> ISSUING)
                 ▼
              ┌─────────┐
              │ ISSUING │
              └────┬────┘
                   │ signatures computed + IssuanceRecord inserted
                   │ (CAS: ISSUING -> ISSUED)
                   ▼
              ┌────────┐
              │ ISSUED │ (terminal, idempotent replay returns same sigs)
              └────────┘

   Side branches (terminal):
     UNPAID -> EXPIRED (TTL elapsed)
     PENDING -> EXPIRED
     PAID -> EXPIRED (rare; quote paid but never minted within TTL)
     any -> FAILED (operator-initiated triage)
```

Transitions are expressed as conditional UPDATE per `research.md`
R3:

```sql
UPDATE mint_quote
SET lifecycle_state = 'ISSUING',
    updated_at = now()
WHERE quote_id = ?
  AND lifecycle_state = 'PAID';
-- row count == 1 -> proceed
-- row count == 0 -> re-read; either idempotent replay or reject
```

---

## Entity: IssuanceRecord

Append-only ledger of every successful mint issuance. Keyed by
`quote_id` for idempotent NUT-19 replay. Stores the
output-fingerprint hash + the resulting `BlindSignature` list
serialised as JSON.

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `quote_id` | `VARCHAR(64)` | PK, NOT NULL, FK -> `mint_quote.quote_id` | One issuance per quote |
| `outputs_hash` | `CHAR(64)` | NOT NULL | SHA-256 of sorted (amount, keyset_id, B_) per output (research R4) |
| `signatures_json` | `JSONB` | NOT NULL | Array of `BlindSignature` payloads — returned verbatim on retry |
| `keyset_id` | `VARCHAR(64)` | NOT NULL | Active keyset used for these signatures |
| `total_amount` | `BIGINT` | NOT NULL, > 0 | Sum of output amounts, MUST equal `mint_quote.amount` |
| `issued_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | DB clock |

**Indexes**:
- PRIMARY KEY (`quote_id`)
- UNIQUE on (`quote_id`, `outputs_hash`) — defence in depth; the PK
  already provides uniqueness

**Append-only contract**: no UPDATEs, no DELETEs from application
code. A row in `issuance_record` is the witness that
`mint_quote.lifecycle_state == ISSUED` and answers the FR-003
"return the previously signed promises" query in one indexed
lookup.

---

## Entity: WebhookEvent

Append-only record of every payment webhook the mint receives, with
the resolved outcome. Keyed by `(provider, provider_event_id)` per
FR-006.

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `provider` | `VARCHAR(64)` | PK part, NOT NULL | Stable gateway identifier (research R6) |
| `provider_event_id` | `VARCHAR(255)` | PK part, NOT NULL | Provider's event id (Lightning payment hash, Stripe `evt_…`, etc.) |
| `quote_id` | `VARCHAR(64)` | NOT NULL, FK -> `mint_quote.quote_id` | The quote this event references |
| `amount` | `BIGINT` | NOT NULL, > 0 | Amount reported by the webhook |
| `unit` | `VARCHAR(16)` | NOT NULL | Unit reported by the webhook |
| `payment_method` | `VARCHAR(32)` | NOT NULL | Method reported by the webhook |
| `signature_digest` | `CHAR(64)` | NULLABLE | SHA-256 of the signature header value (for forensics) |
| `outcome` | `VARCHAR(32)` | NOT NULL | See outcome enum below |
| `received_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | DB clock |
| `raw_body_compressed` | `BYTEA` | NULLABLE | gzip(JSON) raw body — optional, retention policy out of scope |

**Indexes**:
- PRIMARY KEY (`provider`, `provider_event_id`)
- INDEX on `quote_id` (operator dashboards)
- INDEX on `outcome` (alerting queries)
- INDEX on `received_at`

**Outcome enum** (string):
- `accepted` — first delivery, matched, advanced quote to `PAID`
- `amount_mismatch` — amount didn't match the quote's authorised
  amount
- `unit_mismatch` — unit didn't match
- `method_mismatch` — payment method didn't match
- `duplicate` — same `(provider, provider_event_id)` already seen;
  no state mutation
- `tamper` — same `provider_event_id` paired with a different
  amount or quote on a later delivery; surface to operator
- `unsigned_rejected` — signature missing in a profile that
  requires it
- `signature_invalid` — signature present but didn't verify
- `expired` — quote already expired; no transition
- `noop` — quote already in a non-PENDING state where transition
  is meaningless (e.g. already `ISSUED`)
- `orphan` — webhook arrived before the quote row was created
  (queued for reconciliation; out of immediate scope)

**Append-only contract**: same as `IssuanceRecord`. No
UPDATEs/DELETEs from application code.

---

## State Transition Invariants (summary)

For every `MintQuote` row across its lifetime:

- At most one row in `IssuanceRecord` (PK on `quote_id`).
- For every `lifecycle_state = PAID` transition, there is exactly
  one `WebhookEvent` row with `outcome = accepted`,
  `quote_id = mint_quote.quote_id`,
  `amount = mint_quote.amount`,
  `unit = mint_quote.unit`,
  `payment_method = mint_quote.payment_method`.
- `WebhookEvent.amount` may legitimately differ from
  `mint_quote.amount` only when the row's `outcome` is one of the
  `*_mismatch` / `tamper` variants.
- Total issued amount across the mint =
  `SUM(mint_quote.amount) WHERE lifecycle_state = ISSUED`
  = `SUM(issuance_record.total_amount)` (the FR-001 invariant
  expressed as a daily reconciliation query).

---

## Flyway Migrations

Three SQL files in
`cashu-mint-jpa/src/main/resources/db/migration/`:

1. `V20260522_001__create_mint_quote.sql` — `mint_quote` table +
   indexes + Envers `mint_quote_aud` + `REVINFO`.
2. `V20260522_002__create_issuance_record.sql` — `issuance_record`
   table + indexes + FK to `mint_quote`.
3. `V20260522_003__create_webhook_event.sql` — `webhook_event`
   table + indexes + FK to `mint_quote`.

Migration ordering: `mint_quote` first (target of the FKs);
`issuance_record` and `webhook_event` order is interchangeable.

Online-safe: all migrations are pure DDL on new tables; no
existing-table column additions in this feature; brief
table-creation locks only.

---

## Out of Scope (Phase 2 / future)

- Retention policy for `webhook_event.raw_body_compressed`.
- Archival of `issuance_record` rows (append-only growth).
- Cross-mint linking when one PostgreSQL instance hosts multiple
  mints (today: one mint per instance).
- Migration of the existing in-process `QuoteStatusService` cache
  to be backed by Redis vs. simple in-memory LRU (today: in-memory
  is sufficient because the cache is read-through only).
