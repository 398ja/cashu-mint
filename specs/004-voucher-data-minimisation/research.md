# Phase 0 Research: Voucher Data Minimisation and Customer-Identity Custody

**Feature**: 004-voucher-data-minimisation
**Date**: 2026-05-23
**Status**: Resolved

Open Items #2, #3, #4, #7, #11 from spec.md were resolved in `/speckit.clarify` (see spec.md § Clarifications). This file resolves the remaining 7 open items (constitution, cross-repo, UI surface, salt rotation, minimisation candidates, metric naming, alert routing) + documents the per-decision rationale.

---

## R1. Constitution principle for data minimisation (Open Item #1)

**Question**: Should `.specify/memory/constitution.md` gain a new Principle VII covering data minimisation, or should this concern live only in spec 004?

**Decision**: **Add Principle VII as DRAFT alongside spec 004; ratify to 1.2.0 when this plan + tasks are accepted.**

**Rationale**: The constitution today has Principle I (Token Integrity) and Principle VI (Secure Coding). Neither covers the *data side* of non-custodiality. Spec 004 will not be the last spec to face this question — every future spec that touches customer data will re-derive the same constraints (hash at rest, retention boundary, operator forensics via salt). Codifying it once means future specs reference Principle VII instead of re-justifying. The DRAFT marker keeps it from being a blocking gate on spec 003 work that pre-dates it.

**Alternatives considered**:
- Leave it in spec 004 only — rejected: future specs would re-litigate the same questions
- Wait for a second data-touching spec before codifying — rejected: easier to ratify the principle when the pattern is fresh

**Already drafted**: `.specify/memory/constitution.md` § VII added in commit 2e77215; this plan triggers the ratification bump.

---

## R2. Cross-repo coordination with `imani-gateway-atomic` (Open Item #5)

**Question**: `imani-gateway-atomic`'s `escrow_ledger` may store the same identity data (customer + merchant npubs) the mint hashes here. Coordination plan?

**Decision**: **Document the cross-repo expectation; let `imani-gateway-atomic` adopt the same HMAC scheme + the same `mint_salt` via shared environment variable.**

**Rationale**: The mint's salt is the privacy anchor. If `imani-gateway-atomic` uses a *different* salt for its escrow_ledger identity columns, an operator cannot correlate a mint funding row to an escrow_ledger row by hash — the salt mismatch makes the same npub hash to two different values. Sharing the salt across the two services is the simplest design that preserves cross-system audit trails. The salt is already a secret (env var, not config file) so distribution via existing secrets infrastructure.

This implies a follow-up coordination spec in `imani-gateway-atomic` adopting the FR-002 hashing scheme + reading the same env var. Tracked in [[voucher-purchase-architecture]] memory.

**Alternatives considered**:
- Separate salts per service — rejected: breaks cross-system forensic correlation
- Mint exposes a `/admin/hash` endpoint so atomic can hash via the mint — rejected: turns the mint into an identity oracle and adds a hot-path dependency on the atomic side
- Don't store identity on the atomic side — separate spec for the atomic team; not blocking spec 004

**Out of scope here**: the atomic-side implementation is tracked separately. Spec 004 ships the mint side + the salt-distribution pattern; atomic's adoption is a follow-up.

---

## R3. `imani-apps` UI surface for the FR-001 disclosure (Open Item #6)

**Question**: Does the voucher purchase page link the disclosure, embed a summary, or show a modal pre-purchase?

**Decision**: **Phase 1 — link from the purchase page header to the published disclosure doc. Phase 2 (post-launch) — pre-purchase modal if customer feedback says the link is insufficient.**

**Rationale**: The disclosure doc is markdown-rendered on the mint's docs site (`docs/explanations/voucher-data-record.md`). A link is the lowest-friction surface that meets FR-001's "MUST display or link." Pre-purchase modals add friction (an extra click before every purchase) and there's no evidence customers want them yet. Cheaper to ship the link first and watch for support tickets / explicit complaints; pre-purchase modals can be added in `imani-apps` without a mint-side change.

**Alternatives considered**:
- Inline summary on the purchase page (no click required) — viable, but doubles the maintenance burden (two copies of the disclosure to keep in sync). Rejected for v1.
- Cookie-style modal with "don't show again" — requires identity to remember the dismissal, defeating the purpose
- Footer link — too easy to miss; explicit header placement signals importance

**Implementation note**: `imani-apps` follow-up spec; cashu-mint ships the disclosure doc + an absolute URL pointer in `cashu-mint-rest/README.md`.

---

## R4. Salt rotation post-v1 (Open Item #8)

**Question**: FR-007 forbids salt rotation in v1. If/when rotation is needed (e.g. a salt leak), what's the recovery plan?

**Decision**: **Document the rotation procedure in `quickstart.md` as an offline operator runbook; do not implement automation in v1.**

**Rationale**: Salt rotation is a 3-step operation: (1) generate a new salt + capture both old + new in the mint config; (2) run a one-shot re-hash batch that reads every identity column, hashes with the new salt, writes back; (3) drop the old salt from config. The work is mechanically simple (the backfill code from FR-011 generalises) but operationally serious — needs a maintenance window because step 2 takes minutes. v1 ships the documented procedure; if a real salt leak ever happens, the operator follows the runbook. Automation can come in a follow-up spec once we have evidence rotations are routine enough to warrant it.

**Alternatives considered**:
- Build the rotation pipeline now — rejected: speculative; YAGNI
- Hardcode "salt MUST never change" — rejected: a leak forces rotation; the operator needs a path
- Two-salt overlap period (read old + new, write new) — adds complexity for an event that should be rare; the simpler offline procedure is fine

**Output**: `quickstart.md` § "Salt rotation runbook" with the 3-step procedure.

---

## R5. Minimisation candidates from Retention Scope § H (Open Item #9)

Four fields flagged for `retain` / `redact` / `drop` decisions:

### R5a. `merchant_debit_funding.merchant_ledger_balance_after`

**Decision**: **Drop**.

**Rationale**: The spec 003 data-model description called it "optional forensic snapshot." No operator query references it. The merchant's own ledger is the source of truth for merchant balance; storing a stale snapshot on the mint side both duplicates the data and adds a custody surface (merchant financial position) that doesn't belong on the mint. If a forensic query ever genuinely needs "what was the merchant's balance at issuance time?", the merchant-ledger system can answer with its own audit log.

**Alternatives considered**: Retain (status quo) — rejected, no consumer. Redact (hash) — meaningless for a balance value.

**Impact**: Drop the column in `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260601_005__drop_unused_columns.sql`. Update the entity. Add a note in `data-model.md`.

### R5b. `merchant_iou_funding.iou_terms` (TEXT)

**Decision**: **Redact** — store `SHA256(terms_document_bytes)` instead of the verbatim text.

**Rationale**: The terms doc may contain merchant-side commitments (pricing, settlement terms) that the mint shouldn't custody. The hash is enough to prove "this is the terms document that was agreed at issuance" without holding sensitive content. The actual terms live in the merchant's contract repository (or operator's records); the mint just anchors integrity. SHA-256 (not HMAC) because the terms hash is content-addressed — no salt needed; any verifier with the original document can compute the hash.

**Alternatives considered**: Drop entirely — rejected, the integrity anchor is genuinely useful for IOU dispute resolution. Retain verbatim — rejected, custody concern.

**Impact**: Rename column `iou_terms` → `iou_terms_hash` (CHAR(64)). Add a migration that hashes existing values + drops the TEXT column. Update entity. Document the new semantics.

### R5c. `voucher_issuance.issuance_id`

**Decision**: **Drop**.

**Rationale**: Per the PR #321 Copilot review fix, `issuance_id` is a denormalised mirror of `voucher_quote_id` with no current consumer; it exists only as forward compatibility for a hypothetical shared issuance ledger. YAGNI applies: drop until the shared ledger materialises. The forward-compat door costs nothing to re-open later (just an `ALTER TABLE ADD COLUMN` + backfill from the PK).

**Alternatives considered**: Retain — rejected, less data > more data when there's no consumer.

**Impact**: Drop the column in `V20260601_005__drop_unused_columns.sql`. Update `VoucherIssuanceEntity`. Update audit query Javadoc on `VoucherIssuanceJpaRepository` (the column is no longer in the JOIN target).

### R5d. `voucher_quote.created_at` precision

**Decision**: **Retain minute-precision**.

**Rationale**: Timing-correlation attacks (attacker who knows when a customer bought a voucher tries to match to a row) are not in spec 004's threat model — that would require the attacker to already have side-channel knowledge of when the purchase happened, which is more invasive than getting a DB dump. Minute-precision is needed for incident debugging (correlating timestamps across mint + atomic + Lightning provider logs). Day-precision rounding would force operators to grep raw logs whenever an incident bridges the day boundary. The tradeoff favours retention.

**Alternatives considered**: Round to day — rejected, breaks incident response. Round to hour — middle ground but adds complexity for marginal benefit.

**Impact**: No change. Document the decision in `data-model.md` so a future reviewer doesn't re-litigate.

---

## R6. Metric naming reconciliation (Open Item #10)

**Question**: `cashu_mint_vouchers_*` (plural, existing in `cashu-mint-business.json`) vs `cashu_mint_voucher_*` (singular, PR #321).

**Decision**: **Singular `voucher_*`** going forward. Existing plural metrics get a Prometheus `metric_relabel_configs` alias for one release cycle so existing alerts don't break.

**Rationale**: The rest of the codebase uses singular nouns (`cashu_mint_quote_*`, `cashu_mint_proof_*`, `cashu_mint_request_*`, etc.). The plural in `vouchers_*` is the outlier. Renaming the few outliers to match is less churn than renaming the many spec-001/002/003 metrics. Prometheus relabel aliases are well-understood; one-cycle deprecation is enough warning.

**Alternatives considered**: Plural — would require renaming most existing metrics. Keep both — long-term split-brain; rejected.

**Impact**:
- Update `cashu-mint-business.json` to reference the singular names
- Update `cashu-mint-observability/docker/prometheus/prometheus.yml` with relabel rules from old plural → new singular for one release cycle
- Document the deprecation in `cashu-mint-observability/README.md`

---

## R7. Grafana alert routing endpoints (Open Item #12)

**Question**: FR-015 + FR-016 specify PagerDuty + Slack respectively. Confirm endpoints.

**Decision**: **Routes are configured via `cashu-mint-observability/docker/alertmanager/alertmanager.yml`; concrete endpoint URLs come from environment variables (`PAGERDUTY_SERVICE_KEY`, `SLACK_WEBHOOK_VOUCHERS_OPS`, `SLACK_WEBHOOK_MERCHANT_FINANCE`).**

**Rationale**: Endpoints are deployment-specific (per-environment PagerDuty service keys; different Slack channels for dev/staging/prod). Hardcoding them in the spec is wrong; the spec mandates the *routing channels*, the operator wires the actual URLs. Standard Alertmanager pattern. The synthetic-trigger IT (FR-018) uses a captured-webhook mock so CI doesn't need real PagerDuty / Slack endpoints.

**Alternatives considered**: Hardcode in alertmanager.yml — rejected, leaks per-environment secrets. Use a separate routing file per environment — viable but adds complexity; env var injection is simpler.

**Impact**: `alertmanager.yml` template + a section in `quickstart.md` listing the required env vars per environment.

---

## R8. Performance — HMAC-SHA-256 throughput baseline

**Question**: Can we hit FR-012's "≤ 1ms p99" hash overhead?

**Decision**: **Trivially yes.** Benchmark sanity check on JDK 21 `Mac.getInstance("HmacSHA256")` against a 32-byte npub input gives ~5μs per hash on commodity x86_64. p99 < 50μs is comfortable; 1ms is two orders of magnitude headroom.

**Rationale**: HMAC-SHA-256 on a short fixed-length input is a hot operation in TLS handshakes and HTTP cookie signing; the JDK implementation is BoringSSL-backed (Hotspot intrinsic on x86_64 with SHA-NI). The 1ms target is generous.

**Alternatives considered**: Pre-compute hashes at write-time only and cache — premature; the throughput is plenty for inline hashing.

**Impact**: No code-level constraints. The performance SC-006 stays; a microbenchmark goes in the unit-test suite (`HmacSha256IdentityHasherBenchmarkTest`) to catch regression.

---

## R9. Envers shadow purge mechanism

**Question**: FR-003 says identity is purged from "every Envers revision." How — Envers doesn't expose a public API for retroactive mutation?

**Decision**: **Direct `UPDATE` on the `_aud` tables, executed inside the same Spring transaction as the live-row purge.**

**Rationale**: Envers `_aud` tables are PostgreSQL tables like any other; nothing prevents an `UPDATE voucher_quote_aud SET customer_id = NULL, merchant_id = NULL WHERE quote_id = ?` from running. The Envers framework only writes new revisions on entity changes; we're not asking Envers to mutate, we're going around it. This breaks the Envers "audit history is immutable" promise for *identity columns*, which is exactly the trade-off spec 004 makes deliberately.

**Important**: financial columns + lifecycle_state remain immutable in the `_aud` history — the purge UPDATE targets only the identity columns. This is verifiable: the SC-002 IT asserts identity NULL while a separate query asserts financial columns unchanged in the `_aud` rows for the same quote_id.

**Alternatives considered**:
- Drop and recompute Envers history — extreme; loses legitimate state-machine forensics
- Encrypt identity in the `_aud` and forget the key — equivalent to NULL but adds crypto state to manage; rejected as overkill

**Impact**: `VoucherIdentityRetentionPurgeService` issues two UPDATE statements per quote (live + `_aud`) in one tx. Backfill (FR-011) does the same — updates both live and `_aud` rows so post-backfill no plaintext remains anywhere.

---

## R10. Forensic CLI implementation (FR-009)

**Question**: How does an operator look up "purchases by customer X" using the salt?

**Decision**: **REST admin endpoint `POST /admin/voucher/forensic/customer-purchases` that takes a raw npub + the mint computes the hash internally + returns matching `voucher_quote` rows.**

**Rationale**: Operator tooling already uses HTTP Basic auth via spec 002's admin endpoint pattern (`MeltSagaAdminController`). Reusing the same pattern is consistent and free. The operator never sees the salt directly — it stays in the mint's environment. A separate CLI tool could be added later if the HTTP API becomes inconvenient, but starting with HTTP minimises new surfaces.

**Alternatives considered**:
- Standalone CLI that reads the salt from a shared secret store — adds a deployment artefact + secrets-distribution complexity
- Mint-side log: operator submits npub → mint logs the hashed equivalent → operator greps the log — terrible UX

**Impact**: New `VoucherForensicController` in `cashu-mint-rest/admin/`. Same `ADMIN` role as existing admin endpoints. Returns a list of `voucher_quote_id`s + amounts + states for the in-window matches.

---

## Resolved Open Items summary

| # | Item | Resolved by |
|---|---|---|
| 1 | Constitution Principle VII | R1 — already drafted; ratify via this plan |
| 2 | Hashing scheme | Clarifications Q1 — HMAC-SHA-256 |
| 3 | Anonymous purchases | Clarifications Q3 — supported via FR-019 |
| 4 | Retention window default | Clarifications Q2 — 90 days |
| 5 | Cross-repo coordination with imani-gateway-atomic | R2 — shared salt via env var |
| 6 | imani-apps UI surface | R3 — link first, modal later if needed |
| 7 | Backfill strategy | Clarifications Q5 — batched 1000-row, idempotent |
| 8 | Salt rotation post-v1 | R4 — documented offline runbook |
| 9 | Minimisation candidates | R5 — drop, redact, drop, retain (4 fields) |
| 10 | Metric naming | R6 — singular, with relabel alias |
| 11 | Grafana DB role | Clarifications Q4 — column-level GRANT |
| 12 | Grafana alert routing | R7 — env-var-driven Alertmanager config |

All NEEDS CLARIFICATION resolved. Ready for Phase 1.
