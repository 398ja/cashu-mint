# Feature Specification: Voucher Data Minimisation and Customer-Identity Custody

**Feature Branch**: `004-voucher-data-minimisation`
**Created**: 2026-05-23
**Status**: Draft
**Input**: Follow-up to spec 003 (`003-voucher-quote-durability`) — data-custody question surfaced during PR #321 review.
**Source Repository**: `cashu-mint`
**Code Touch Points (mint-side)**:
- `cashu-mint-jpa/src/main/java/.../jpa/entity/VoucherQuoteEntity.java` — stores `customer_id` (npub), `merchant_id`, `request_hash`
- `cashu-mint-jpa/src/main/java/.../jpa/entity/CustomerPaymentFundingEntity.java` — stores `customer_id`, `provider_event_id`, mirror of webhook payment
- `cashu-mint-jpa/src/main/java/.../jpa/entity/MerchantDebitFundingEntity.java` / `MerchantIouFundingEntity.java` — store `merchant_id`
- `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260524_001..004__*.sql` — Envers `_aud` shadows make every revision permanent
- `cashu-mint-rest/src/main/java/.../rest/voucher/VoucherIdempotencyKeyFilter.java` — `voucher_idempotency_key.response_body_json` durably caches full responses

**Cross-Repo Sibling Specs (out of scope here)**:
- `imani-gateway-atomic` voucher orchestration follow-up (the eager `funding_ref` propagation tracked as spec-003 T010 retargeted). Whatever it stores in its escrow_ledger has its own data-custody question that belongs in that repo's spec backlog.
- `imani-apps` voucher front-end disclosure / consent surface (if FR-008 below mandates user-visible disclosure).

## Background and Problem Statement

Spec 003 closed a real token-integrity hole — the "skip payment check" voucher path was creating spendable Cashu proofs with no record at all, which would silently inflate the mint's liability. The fix (FR-002: every voucher proof traces to a durable funding row) is sound for **token** non-custodiality but introduces a new kind of custody the project hasn't explicitly discussed: **data custody**.

The mint now holds, durably and with permanent Envers revision history, the tuple:

```
(customer_npub, merchant_npub, amount, unit, timestamp, payment_method, lifecycle_state)
```

…per voucher purchase. Before spec 003 this lived only in an in-memory `VoucherQuoteRegistry` with Caffeine TTL and was erased by every restart. Now it's PostgreSQL + Envers — permanent, queryable, and present in every backup.

Three things compound the change:

1. **Envers shadows** — `voucher_quote_aud`, `voucher_funding_aud`, and the per-variant `_aud` tables retain every revision of these rows. Operators can reconstruct a customer's complete purchase history at any point in time.
2. **`voucher_idempotency_key.response_body_json`** — full response bodies are cached (FR-009 mandate: "same response on retry"). Those bodies include voucher metadata that may inadvertently include customer identity.
3. **No retention policy** — there is no purge job for these tables today. The `VoucherIdempotencyKeySweeper` prunes only by `expires_at` (24h default); the underlying quote/funding/issuance rows live forever.

### What Cashu's non-custodial property covers — and doesn't

Cashu's non-custodial property is **cryptographic**: blind signatures mean the mint cannot link an issued proof to a future swap/melt. That property is intact after spec 003 — proofs are still bearer tokens, the mint cannot enumerate a customer's spends.

What spec 003 changes is **data custody at the funding stage**. The customer's npub is recorded alongside what they bought, when, and for how much. A privacy-conscious customer making a voucher purchase is implicitly opting into a durable record they didn't have before.

If the project pitches itself to customers as "your transactions are private," that claim now has a gap. Worth examining deliberately rather than emergently.

## Constitution Alignment

This feature is governed by the cashu-mint Constitution v1.1.0
(`.specify/memory/constitution.md`). It also surfaces a **gap** in the constitution.

- **Principle I — Token Integrity (NON-NEGOTIABLE)**: this spec MUST NOT weaken the spec-003 funding gate. Any data-minimisation measure that risks re-opening the silent-inflation hole is rejected.
- **Principle II — Protocol Compliance (Cashu NUTs)**: vouchers remain a vendor extension; nothing here changes that.
- **Principle III — Clean Architecture**: identity-bearing data should be separable from financial data so retention can be tuned independently.
- **Principle IV — Testing Discipline**: data-minimisation choices need explicit regression tests so a future change doesn't inadvertently re-introduce raw PII storage.
- **Principle VI — Secure Coding & Code Quality**: covers operational security (auth, rate-limit) but not data minimisation per se.

**Constitution gap (open question, see Open Items below)**: there is no existing principle covering *data minimisation*, *customer-identity custody*, or *retention*. This spec is forcing a decision about whether such a principle belongs in the constitution and, if so, what form it takes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A privacy-conscious customer can tell what the mint records about them (Priority: P1)

A customer about to purchase a voucher MUST be able to determine, before purchase, what identity-bearing data the mint will durably retain. This is a transparency baseline; nothing the mint stores about a customer should be undisclosed.

**Why this priority**: This is the existential trust question. If the mint stores customer identity without disclosure, the non-custodial pitch is materially misleading. P1 because it gates the legitimacy of every other change in this spec.

**Independent Test**: A document (`docs/explanations/voucher-data-record.md`) exists and is linked from the customer-facing voucher purchase UI. A customer reading it can answer:
- Which of my identity fields are stored?
- For how long?
- Who can read them?
- Under what circumstances are they shared with third parties?

**Acceptance Scenarios**:

1. **Given** the customer is on the voucher purchase page, **When** they look for a "what is recorded" link or disclosure, **Then** they find a published document describing the durable record.
2. **Given** the customer makes a voucher purchase, **When** the record is created, **Then** the durable fields match those listed in the document — no undisclosed columns.
3. **Given** the disclosure document, **When** an auditor compares it to the actual `voucher_quote` + `voucher_funding` schemas, **Then** every identity-bearing column is accounted for.

---

### User Story 2 — Customer identity is hashed at rest (Priority: P1)

The customer's npub (and merchant's npub) MUST be stored as a salted hash, not as raw plaintext, in every voucher-related table including Envers shadows.

**Why this priority**: A read-only DB dump (backup compromise, ops-side leak) MUST NOT yield a customer enumeration. Hashing at rest costs nothing in performance and removes the most obvious attack vector. P1 because the mitigation is cheap and the protection is real.

**Independent Test**: An integration test that creates a voucher purchase and then issues a raw SQL `SELECT customer_id, merchant_id FROM voucher_quote` returns hex-encoded SHA-256 digests, not raw npubs. Same for the `_aud` shadows.

**Acceptance Scenarios**:

1. **Given** a customer with npub `npub1...`, **When** they purchase a voucher, **Then** the persisted `voucher_quote.customer_id` is `SHA256(npub || mint_salt)`, not the raw npub.
2. **Given** an operator with the mint salt, **When** they need to verify a specific customer's purchase, **Then** they can re-derive the hash and match — but cannot enumerate all customers from a DB dump alone.
3. **Given** an attacker with a DB backup but no salt, **When** they query for a known npub, **Then** no rows match.

---

### User Story 3 — Identity fields decay after a retention window (Priority: P2)

Identity-bearing columns on `voucher_quote` (and equivalents on funding child tables) MUST be nullable-out after a configurable retention window once the voucher quote has reached a terminal state (`ISSUED` / `EXPIRED` / `FAILED`). Financial fields (amounts, lifecycle state, funding link) MUST be retained — they back the FR-002 invariant indefinitely.

**Why this priority**: A real retention boundary turns "we hold this forever" into "we hold this for 90 days." Reduces the long-tail exposure from any single breach. P2 because Story 2 already reduces exposure significantly; retention is the second line of defence.

**Independent Test**: An integration test that seeds an `ISSUED` voucher quote with a backdated `updated_at`, runs the retention sweeper, and asserts (a) `customer_id` and `merchant_id` are NULL on both the live row and every Envers revision past the retention window, AND (b) the financial fields and `funding_id` are intact.

**Acceptance Scenarios**:

1. **Given** a voucher quote in `ISSUED` state more than `N` days old, **When** the retention sweeper runs, **Then** `customer_id` and `merchant_id` are nullified on the live row.
2. **Given** the same quote, **When** queried via the Envers audit API, **Then** the historical revisions ALSO have the identity fields nullified (`REVTYPE=2 MOD`) — the audit shadow MUST NOT preserve PII past the retention window.
3. **Given** the FR-002 daily reconciliation query, **When** it runs against the post-purge data, **Then** the funding-row → issued-quote invariant still holds (no orphans).

---

### User Story 4 — Idempotency cache scrubs identity from cached bodies (Priority: P2)

`voucher_idempotency_key.response_body_json` MUST NOT cache response bodies containing customer identity verbatim. The cache stores enough to replay the response shape, but identity fields in the body MUST be either omitted or hashed in the same scheme as Story 2.

**Why this priority**: The idempotency cache is duplicate state — if `voucher_quote` hashes identity but the idempotency replay still serves raw npubs in the cached body, the protection is illusory. P2 because the 24h TTL limits exposure even without this change, but the gap is real.

**Independent Test**: A test that triggers an Idempotency-Key replay and inspects `response_body_json` directly via SQL. Identity fields in the JSON match the hashing scheme from Story 2.

**Acceptance Scenarios**:

1. **Given** a successful voucher purchase that returns a customer npub in the response body, **When** the idempotency row is written, **Then** `response_body_json` either omits the npub or stores its salted hash.
2. **Given** a replay using the same Idempotency-Key, **When** the cached response is served, **Then** the bytes returned to the caller MAY contain the original (controller-rendered) identity OR a redacted variant — the spec's preference is **redacted**, but this MUST be a deliberate, documented choice.

---

### User Story 5 — Operator forensics still work (Priority: P2)

After Stories 2-4 land, an operator with appropriate access MUST still be able to:
- Run the FR-002 / SC-001 daily reconciliation (no orphan issuances)
- Run the IOU liability dashboard query
- Answer "which voucher purchases did customer X make?" within the retention window
- Answer "which voucher purchases settled via provider event Y?" indefinitely (provider_event_id is not identity-bearing)

**Why this priority**: Data minimisation that breaks operator forensics is unsustainable. The operator path needs explicit test coverage.

**Independent Test**: The existing SC-001 reconciliation query continues to return 0 rows on a healthy data set. The IOU liability query continues to sum correctly. A "customer purchase history" query using the mint salt + a known npub returns the expected rows for purchases inside the retention window and zero rows for purchases outside it.

**Acceptance Scenarios**:

1. **Given** a healthy mint, **When** the daily SC-001 reconciliation runs, **Then** it returns 0 orphan voucher quotes (unchanged from spec 003).
2. **Given** the operator's salt + a customer npub, **When** they query the audit endpoint, **Then** they see in-window purchases for that customer.
3. **Given** a purchase older than the retention window, **When** the same query runs, **Then** the row appears but `customer_id` is NULL.

---

### User Story 6 — Operator dashboards expose the retained data sensibly (Priority: P2)

The retained financial data MUST be exposed via Grafana dashboards that are glanceable, alert-ready, and **privacy-aware**: aggregate panels read only financial / state columns and work identically before and after retention purge; per-record panels display truncated identity hashes (or a `{purged}` sentinel for purged rows) and never expose raw npubs.

**Why this priority**: spec 003 SC-001 / SC-006 + spec 004 SC-002 already require these queries to run daily. Exposing them in Grafana turns "queries on demand" into "glanceable signals with alert thresholds." Plus, the privacy-aware display pattern is what proves the hashed-at-rest model is operator-usable — without it, operators would reach for the raw DB and circumvent FR-002.

**Independent Test**: Three new Grafana dashboards exist in `cashu-mint-observability/docker/grafana/dashboards/` (Liability, Integrity, IOU). Their queries are inspected and contain zero references to identity columns where aggregate, and only truncated-hash display where per-record. The orphan-issuance alert fires within 5 minutes when a test row is injected with `lifecycle_state='ISSUED' AND funding_id IS NULL`.

**Acceptance Scenarios**:

1. **Given** the Voucher Liability Overview dashboard, **When** the operator loads it, **Then** they see total outstanding liability + per-funding-source stacked breakdown + 30-day trend, with zero identity-column references in any panel query.
2. **Given** the Token Integrity Reconciliation dashboard, **When** the orphan-issuance count is 0, **Then** the single-stat panel is green; **When** an orphan is injected, **Then** the panel turns red and Grafana fires an alert routed to PagerDuty within 5 minutes.
3. **Given** the IOU Liability Tracker dashboard, **When** the operator views an in-window IOU row, **Then** the merchant identifier is shown as a truncated hash (e.g. `4f2a1c…`); **When** they view a post-retention IOU row, **Then** the cell displays `{purged}` instead of NULL or an empty string.
4. **Given** a Grafana user without the `forensic` role, **When** they try to expand a truncated hash, **Then** the dashboard does NOT offer a "reveal" path; identity recovery is exclusively via the FR-009 CLI tool (which holds the salt).
5. **Given** the existing `cashu-mint-business.json` dashboard's `cashu_mint_vouchers_*` (plural) metrics, **When** the new `cashu_mint_voucher_*` (singular) metrics from PR #321 land, **Then** the naming is reconciled into a single convention and the business dashboard is updated accordingly.

---

### Edge Cases

- A retention sweep crashes mid-batch. Restart MUST be safe (idempotent on already-nullified rows).
- A customer purchases a voucher with an empty / missing `customer_id` (e.g. anonymous flow). The hash function MUST handle null/empty input deterministically.
- Salt rotation. If the mint operator rotates the salt, in-window queries by old hash break; old queries by new hash also break. Spec MUST decide: forbid salt rotation, OR re-hash batch.
- Envers revision audit for a post-purge query. The `_aud` table for a row that has been purged MUST surface a "PURGED" revision marker so the operator can tell purge happened (vs row never existed).
- A merchant who is also a customer (npub appears in both fields). Both fields hash with the same salt; collisions across columns are not security-relevant (hashes are stable across columns).
- Existing rows from spec 003 in production. Migration story: backfill hashes for existing rows during deploy, OR forward-only (only new rows are hashed).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A customer-facing disclosure document MUST exist (`docs/explanations/voucher-data-record.md`) listing every identity-bearing column the mint stores per voucher purchase, the retention period, the hashing scheme, and the access controls. The document MUST be linked from the `imani-apps` voucher purchase UI (cross-repo coordination tracked separately). [US1]
- **FR-002**: Every identity-bearing column on `voucher_quote`, `customer_payment_funding`, `merchant_debit_funding`, `merchant_iou_funding`, and their `_aud` shadows MUST store `SHA256(value || mint_salt)` rather than raw plaintext. The salt MUST come from a configuration property (`cashu.mint.voucher.identity-salt`, no default — explicit operator action required) and MUST be the same salt across all tables. [US2]
- **FR-003**: A scheduled purge job MUST nullify `customer_id` and `merchant_id` on `voucher_quote` rows in terminal states (`ISSUED` / `EXPIRED` / `FAILED`) older than `cashu.mint.voucher.identity-retention` (default `PT2160H` = 90 days). The purge MUST also nullify the same fields in every Envers revision for that row. Financial fields (`face_value`, `charged_amount`, `fee`, `unit`, `funding_id`, `lifecycle_state`) MUST be retained. [US3]
- **FR-004**: The purge job MUST be idempotent — re-running on the same row MUST be a no-op. [US3]
- **FR-005**: `voucher_idempotency_key.response_body_json` MUST NOT cache response bodies containing identity in plaintext. Implementation MAY redact, MAY hash, MAY scrub specific JSON paths — the choice is documented in the spec's `research.md`. [US4]
- **FR-006**: The `request_hash` field on `voucher_quote` is already a hash and is NOT in scope (it's tamper detection, not identity). [Clarification — covered for FR-002 boundary.]
- **FR-007**: Salt rotation MUST be forbidden in v1. If a future spec adds rotation, it MUST include a re-hash batch + a documented downtime window. [Edge case mitigation]
- **FR-008**: The `imani-apps` voucher purchase UI MUST display (or link to) the FR-001 disclosure document before the customer commits to the purchase. Cross-repo follow-up; tracked here so the spec coordination point is explicit. [US1]
- **FR-009**: The operator forensic query path (audit endpoint or admin tool) MUST accept a raw npub + the mint salt and compute the hash internally to look up in-window rows. An operator MUST NOT have to compute the hash by hand. [US5]
- **FR-010**: A purged Envers revision MUST be recoverable as "purged on date X" so operators can distinguish "row was purged" from "row never existed." Implementation MAY use a sentinel value, a separate `voucher_quote_purge_log` table, or an Envers metadata extension. [US5 / Edge case]
- **FR-011**: Migration of existing spec-003 rows: on first boot with FR-002 wired, the mint MUST backfill hashes for existing raw-npub rows in a single transaction (or batched, but idempotent). After backfill, no plaintext npub remains. [Edge case]
- **FR-012**: Performance ceiling: per-request hash overhead MUST be ≤ 1ms at p99. Backfill MUST complete in ≤ 5min for a 10M-row table (offline-safe; brief table lock acceptable).
- **FR-013**: A PostgreSQL data source MUST be added to the `cashu-mint-observability` Grafana provisioning (`docker/grafana/provisioning/datasources/`) so dashboards can query the durable state directly. The credentials MUST come from environment variables; the data source MUST be read-only at the database role level. [US6]
- **FR-014**: A `voucher-liability-overview.json` Grafana dashboard MUST exist with at least four panels: (1) outstanding voucher liability single-stat (`SUM(face_value) WHERE lifecycle_state='ISSUED'`); (2) liability per funding source stacked breakdown (`GROUP BY funding_source`); (3) 30-day liability trend; (4) per-funding-source 30-day issuance rate. All four panel queries MUST be inspectable and MUST NOT reference any identity column. [US6]
- **FR-015**: A `voucher-token-integrity.json` Grafana dashboard MUST exist with: (1) orphan-issuance single-stat (`COUNT WHERE lifecycle_state='ISSUED' AND funding_id IS NULL`) — green at 0, red at >0, with a Grafana alert wired to PagerDuty for sustained >0; (2) issued-vs-funded reconciliation panel (`SUM(face_value) - SUM(funding.amount)` over ISSUED rows) — must stay at 0; (3) stuck-quote count by `lifecycle_state` for non-terminal rows older than 1 hour. [US6 / SC-001 / SC-005]
- **FR-016**: An `voucher-iou-liability.json` Grafana dashboard MUST exist with: (1) outstanding IOU table with columns `iou_id`, truncated `merchant_id` hash (e.g. first 8 chars + `…`), `policy_profile`, `amount`, `iou_due_at`, `status` (`overdue` / `due_soon` / `ok`); (2) policy-drift count panel (IOUs issued under `policy_profile != current_setting`); (3) overdue-IOU alert wired to Slack `#merchant-finance`. The merchant identifier column MUST render `{purged}` for rows whose retention window has elapsed (i.e. `merchant_id IS NULL`). The dashboard MUST NOT offer a hash-reveal action; identity recovery is exclusively via the FR-009 CLI tool. [US6 / Spec 004 retention boundary]
- **FR-017**: The metric naming inconsistency between the existing `cashu-mint-business.json` dashboard (`cashu_mint_vouchers_*` plural) and the PR #321 counters (`cashu_mint_voucher_*` singular) MUST be reconciled into a single convention. The reconciliation choice (and the rename direction) is documented in `research.md`. Dashboards updated to the new convention; old metric names retained as Prometheus relabel aliases for one release cycle so existing alerts don't break. [US6]
- **FR-018**: The Grafana alert rules added by FR-015 + FR-016 MUST have synthetic-trigger integration tests: a test seeds an orphan ISSUED voucher quote and asserts the alert fires through the configured notifier (Alertmanager → webhook → captured in IT). [US6 / Testing Discipline]

### Key Entities

- **MintIdentitySalt** — a single 256-bit value sourced from configuration (`cashu.mint.voucher.identity-salt`). Used to compute `SHA256(value || salt)` for every identity-bearing column. NOT persisted in any database; held in memory only.
- **VoucherIdentityPurgeLog** (optional) — append-only record of purge runs, capturing `(purged_at, retention_cutoff, rows_purged)`. Lets operators correlate a "row exists but identity is null" observation to a specific purge event.
- **VoucherDataDisclosure** (documentation entity) — the `docs/explanations/voucher-data-record.md` document is the source of truth for what's disclosed. Any code-side schema change MUST also update this document.
- **VoucherOperatorDashboards** (configuration entity) — the three JSON files under `cashu-mint-observability/docker/grafana/dashboards/` (`voucher-liability-overview.json`, `voucher-token-integrity.json`, `voucher-iou-liability.json`) are versioned alongside the schema. Any retained-field change to the schema MUST also update at least one panel in these dashboards (proven by the SC-007 CI check).

## Retention Scope — what stays, what goes, and why

FR-003 says "financial fields are retained" — too coarse to gate on. This section articulates *every* retained column against the purpose it serves, so a future reviewer can challenge any field's presence without re-deriving the rationale from first principles.

### Purged at retention boundary (identity, FR-003)

After `cashu.mint.voucher.identity-retention` (default 90 days) elapses since a voucher quote reached `ISSUED` / `EXPIRED` / `FAILED`, these columns are nullified on the live row AND every Envers revision:

- `voucher_quote.customer_id` (hashed npub)
- `voucher_quote.merchant_id` (hashed npub)
- `customer_payment_funding.customer_id` (hashed npub mirror)
- `merchant_debit_funding.merchant_id` (hashed npub)
- `merchant_iou_funding.merchant_id` (hashed npub)

Nothing else is nullified by the retention sweep. Every column listed below survives.

### Retained indefinitely (with rationale)

#### A. Token-integrity audit (Constitution I — non-negotiable)

| Field | Purpose |
|---|---|
| `voucher_quote.face_value` | What was issued — the spendable value the customer received |
| `voucher_quote.charged_amount` | What was paid in — the asset side |
| `voucher_quote.fee` | Mint revenue line; reconciles to the financial ledger |
| `voucher_quote.unit` | Currency context — `face_value=1000` is meaningless without `sat` vs `usd` |
| `voucher_quote.funding_id` (FK) | The link itself — without this the issuance is an orphan and SC-001 fires |
| `voucher_quote.lifecycle_state` | Distinguishes ISSUED from EXPIRED/FAILED; without it an operator cannot tell "issued and outstanding" from "never minted" |
| `voucher_funding.amount` + `unit` | Asset side — MUST equal `voucher_quote.charged_amount` for FR-005 reconciliation |
| `voucher_funding.funding_source` | Discriminator — drives liability bucketing across the three variants |
| `voucher_issuance.*` | Append-only proof of issuance event; backs FR-005 audit query |

These power SC-001 (orphan check) and SC-006 (per-source liability dashboard). Delete any of them and the mint cannot prove its non-inflation invariant.

#### B. Anti-replay (provider event idempotency)

| Field | Purpose |
|---|---|
| `customer_payment_funding.provider` + `provider_event_id` (UNIQUE) | Webhook replay protection. Duplicate webhook delivery MUST NOT create a duplicate funding row. Persists at least as long as the provider's own replay window (Lightning providers may retry for days) |
| `customer_payment_funding.webhook_event_quote_id` | Cross-links the funding row to spec-001's `webhook_event` table. Even after `customer_id` is purged, an operator can trace "this funding came from this provider payment event" without identity |

Not customer identity — external payment-system anchors. Lightning payment hashes are public network data already.

#### C. Merchant-ledger reconciliation (cross-system)

| Field | Purpose |
|---|---|
| `merchant_debit_funding.merchant_debit_id` | Reference into the external merchant-ledger system. Operator can prove "the mint issued vouchers backed by this merchant debit" by joining mint records ↔ merchant ledger, **without** holding the merchant's identity (hashed + later purged) |
| `merchant_debit_funding.merchant_ledger_balance_after` | Optional forensic snapshot. **Minimisation candidate** — see § D below |

#### D. IOU liability tracking (financial instrument)

| Field | Purpose |
|---|---|
| `merchant_iou_funding.iou_id` | The IOU is an actual debt instrument; the id is the contract anchor |
| `merchant_iou_funding.iou_terms` | Free-text contract terms. **Minimisation candidate** — see § H |
| `merchant_iou_funding.iou_due_at` | When the IOU must settle. Drives "overdue IOU" alerts (FR-016) even after merchant_id is purged |
| `merchant_iou_funding.policy_profile` | Spring profile in effect at issuance. Detects policy drift: "this IOU was issued under ALLOW; current policy is DENY — investigate" |

#### E. Cryptographic anchors (privacy-preserving)

| Field | Purpose |
|---|---|
| `voucher_quote.request_hash` | SHA-256 of canonical request body. Anchors FR-009 tamper detection. Already a hash — not identity-bearing. Past retention, still useful for "did this voucher quote correspond to this exact request?" forensics |
| `voucher_issuance.outputs_hash` | SHA-256 over blinded outputs. If a customer disputes "I was issued these tokens," they show the outputs and the mint re-computes the hash. Privacy-preserving — the hash doesn't reveal the proofs themselves |

#### F. State-machine integrity

| Field | Purpose |
|---|---|
| `voucher_quote.lifecycle_state` | CAS invariant carrier. Without it single-issuance breaks |
| `voucher_quote.version` | JPA `@Version` — optimistic locking, prevents lost updates under concurrent writers |
| `voucher_funding.version` | Same |

#### G. Forensic timeline

| Field | Purpose |
|---|---|
| `voucher_quote.created_at` / `updated_at` | "When did this happen?" — required for incident timelines, regulatory audits, and pattern analysis. Timestamp alone is not personally identifying once identity is purged. **Minimisation candidate** — see § H |
| `voucher_funding.created_at` | Same |
| `voucher_issuance.issued_at` | Same |
| `voucher_funding_aud` (Envers) | Every state transition. Identity nullified per FR-003 but the financial state machine history is preserved for operator forensics |

#### H. Minimisation candidates (decisions deferred to research.md)

Four retained fields are weaker than the others. The planning phase MUST resolve each as `retain` / `redact` / `drop` with explicit rationale:

| Field | Question | Default proposal |
|---|---|---|
| `merchant_debit_funding.merchant_ledger_balance_after` | Is "merchant solvent at issuance" data actually queried? Or speculative? | **Drop** unless an operator confirms a real use case |
| `merchant_iou_funding.iou_terms` (TEXT) | Are the terms ever read from the mint, or only from the merchant's contract repo? | **Redact**: store `SHA256(terms_document)` + a pointer; mint proves "this is the terms hash that was agreed" without holding sensitive merchant-side pricing |
| `voucher_issuance.issuance_id` | Pure forward compatibility, no current consumer | **Drop** until the shared issuance ledger materialises; less data > more data |
| `voucher_quote.created_at` precision | Minute-level helps debugging; day-level helps regulatory audit. Does the threat model include timing correlation attacks? | **Retain** minute-precision; revisit if FR-001 disclosure prompts a customer-facing concern |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of `voucher_quote.customer_id` and `voucher_quote.merchant_id` values in production are SHA-256 hex digests (length 64, all-hex characters); 0 rows containing raw npubs. (Daily lint job.)
- **SC-002**: 100% of `voucher_quote` rows in terminal state with `updated_at < now() - retention` have NULL `customer_id` AND NULL `merchant_id`. (Daily reconciliation.)
- **SC-003**: The FR-001 disclosure document exists, is linked from the `imani-apps` voucher purchase UI, and matches the actual schema in CI. (Smoke test that re-renders the document from the schema and diffs against the published version.)
- **SC-004**: The `voucher_idempotency_key.response_body_json` cache contains zero raw npubs. (Daily lint job, applied to live + Envers `_aud`.)
- **SC-005**: The FR-002 daily reconciliation (spec-003 SC-001) continues to return 0 orphan voucher quotes after Stories 2-4 land. The funding gate is not regressed.
- **SC-006**: Mean per-request hash overhead < 1ms; p99 < 1ms; measured via Micrometer.
- **SC-007**: Every retained financial field (per the Retention Scope § A–G above) is referenced by at least one Grafana panel in the three voucher dashboards. (CI check: parse the dashboard JSON, extract SQL/PromQL identifiers, diff against the retained-field list — fail if a field is retained but unsurfaced, fail if a panel references a column not in the retained list.)
- **SC-008**: Per-record Grafana panels (the IOU Liability Tracker rows) display either a truncated hash (8 chars + `…`) for in-window rows or `{purged}` for post-retention rows; zero panels render raw identity columns. (CI check: dashboard JSON inspection + integration test that seeds both row classes and screenshots / diffs the rendered cells.)
- **SC-009**: Synthetic-orphan injection test fires the FR-015 PagerDuty alert within 5 minutes (measured via the integration test from FR-018).
- **SC-010**: The reconciled metric naming convention (FR-017) is enforced — zero references to the deprecated name remain in dashboards after one release cycle. (Lint job over dashboard JSON.)

## Open Items (deferred to research / planning phase)

These need explicit decisions before tasks.md is generated.

1. **Constitution principle for data minimisation** — should `.specify/memory/constitution.md` gain a new principle (call it "Principle VII — Data Minimisation")? If yes, this spec triggers a constitution bump (1.1.0 → 1.2.0). Recommend YES; the constitution today has Principle I covering token integrity but nothing covering data integrity / minimisation.
2. **Hashing scheme details** — SHA-256 vs HMAC-SHA-256 vs Argon2 vs Blake3. SHA-256 + salt is the minimum bar; HMAC-SHA-256 (which is effectively what salt-prepended SHA-256 implements) is the cleaner cryptographic primitive. Argon2 is overkill for a non-password use case. Pick one in research.md.
3. **Per-customer opt-out** — should a customer be able to make a voucher purchase that records NO identity at all (truly anonymous)? Today the funding gate doesn't strictly require `customer_id` (the funding row is identified by `provider_event_id`). Worth exploring whether the column can be optional.
4. **Retention window default** — 90 days is the spec's draft default. EU GDPR-style minimisation often suggests 30 days. Bitcoin-tax records often require 6 years. Pick one (or expose as configurable) in research.md.
5. **Cross-repo coordination with `imani-gateway-atomic`** — that service's `escrow_ledger` may store the same identity data this spec hashes on the mint side. Coordination plan needed so the two repos converge on the same hashing scheme + salt distribution.
6. **`imani-apps` UI surface** — does the voucher purchase page link the disclosure, OR embed a summary, OR show a modal pre-purchase? FR-008 mandates "display or link"; the precise UX is for the front-end follow-up spec.
7. **Backfill strategy detail** — single-transaction (simple, brief table lock) vs batched (no lock, longer overall, more complex). FR-011 currently says "batched, idempotent" but the lock duration calc needs a real-row-count estimate from production.
8. **Salt rotation post-v1** — FR-007 forbids rotation in v1. If/when rotation is needed (e.g. a salt is leaked), the recovery plan needs to be designed up-front, not improvised under incident pressure.
9. **Minimisation candidates from Retention Scope § H** — four retained fields are flagged for explicit `retain` / `redact` / `drop` decisions in research.md: `merchant_ledger_balance_after`, `iou_terms`, `voucher_issuance.issuance_id`, and `created_at` precision. The planning phase MUST resolve each with operator input (does anyone actually query this?).
10. **Metric naming reconciliation (FR-017)** — the existing `cashu-mint-business.json` dashboard uses `cashu_mint_vouchers_*` (plural); PR #321 added `cashu_mint_voucher_*` (singular). Pick one. Recommend the singular form to align with `cashu_mint_quote_*` / `cashu_mint_proof_*` elsewhere in the codebase, but the rename means migrating Grafana queries + leaving relabel aliases for one release cycle.
11. **Grafana data source security model** — FR-013 says the PostgreSQL data source is read-only at the DB role level. Open question: should it be a *separate* role with a deny-list on identity columns (defence in depth), or just rely on dashboard query review (simpler)? Recommend the separate role.
12. **Grafana alert routing** — FR-015 and FR-016 specify PagerDuty + Slack respectively. Confirm the actual notifier endpoints + on-call rotation with the SRE owner before tasks.md is generated.

## Assumptions

- The spec-003 funding gate (FR-002) is in place and unmodified.
- The mint runs single-replica today (per memory notes). Salt distribution across replicas is a future concern.
- Customer npubs are the only identity field stored. Email addresses, phone numbers, IP addresses etc. are NOT stored by the mint and are out of scope for this spec.
- `provider_event_id` (e.g. Lightning payment hash) is considered non-identity-bearing — it's a payment artifact, not a customer identifier. Same for `merchant_debit_id` and `iou_id`.
- Spec-003 ITs in PR #322 do not need to be re-run with hashed identities for correctness (the funding gate doesn't read identity); they will need a separate IT pass once hashing lands, asserting hashes are stored.
- Spec author judgement: PR #321 + #322 should land first, then this spec is the immediate follow-up. Reverting the funding gate to "fix" the data-custody side would be worse than the gap this spec closes — token-integrity wins.
- `cashu-mint-observability` is the host for the new Grafana dashboards (FR-013 — FR-016). The module already ships Prometheus + Grafana + Loki + Alertmanager via `docker/docker-compose.observability.yml` and provisions dashboards via JSON files under `docker/grafana/dashboards/`. No new infrastructure is required.

## Out of Scope

- Customer-side hashing / proof of identity. The customer hands the mint an npub; the mint hashes for storage. The mint does NOT operate a customer-identity registry.
- Encryption at rest beyond hashing. If the threat model needs encryption (e.g. for off-mint Lightning provider data), it's a separate spec.
- Right-to-be-forgotten / GDPR erasure requests beyond the retention window. A separate spec would cover the operator-initiated immediate-erase path.
- Cross-mint privacy (multi-mint deployments). Today: one mint per instance.
- The `imani-gateway-atomic` `escrow_ledger`'s own identity-custody question (separate spec in that repo).
