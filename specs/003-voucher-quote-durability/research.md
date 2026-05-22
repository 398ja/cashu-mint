# Phase 0 Research: Voucher Quote Durability and Funding-Source Binding

**Feature**: 003-voucher-quote-durability
**Date**: 2026-05-22
**Status**: Resolved

---

## R1. Voucher quote table — extend `mint_quote` or sibling?

**Question**: Vouchers are non-standard quotes. Should
`VoucherQuoteEntity` extend `MintQuoteEntity` (joined-subclass)
or be a sibling table?

**Options**:

1. Joined-subclass: `voucher_quote` inherits from `mint_quote`
   via `@Inheritance(JOINED)`. One row in `mint_quote` + one row
   in `voucher_quote` per voucher.
2. Sibling table: `voucher_quote` is independent; its `quote_id`
   namespace coexists with `mint_quote`'s but they don't share
   rows.

**Decision**: Option 2 (sibling).

**Rationale**: Spec 001's `mint_quote` is the canonical NUT-04
record. Vouchers and NUT-04 quotes share almost no fields beyond
`quote_id`, `amount`, `unit`, and `lifecycle_state` — vouchers
need `face_value`, `charged_amount`, `merchant_id`,
`funding_source`, `funding_ref` etc., none of which apply to
NUT-04. Forcing them through one hierarchy means
`mint_quote` accumulates NULL columns over time. Sibling table
keeps `mint_quote` clean; the cost (one extra JOIN for the
funding audit query) is modest with the right indexes.

Invariant: `voucher_quote.quote_id` and `mint_quote.quote_id` do
not overlap. Enforced application-side (the quote-creation paths
target one or the other); asserted by integration test.

---

## R2. `VoucherFunding` polymorphism

**Question**: There are three funding source variants
(customer payment, merchant debit, merchant IOU). How are they
modeled?

**Options**:

1. Single `voucher_funding` table with a `funding_source` enum
   column + variant-specific NULLABLE columns
   (`provider_event_id`, `merchant_debit_id`, `iou_id`).
2. JPA `@Inheritance(JOINED)` with three concrete subclasses,
   each in its own child table.
3. JPA `@Inheritance(SINGLE_TABLE)` with discriminator.

**Decision**: Option 2 (JOINED).

**Rationale**: Each variant has distinct attributes — customer
payment has `provider_event_id`, merchant debit has
`merchant_debit_id` + amount, IOU has `iou_id` + due date. JOINED
inheritance keeps the per-variant columns in their own tables
(no NULL fields) while preserving polymorphic queries via the
parent `voucher_funding` table. Option 1's NULL columns hide
intent. Option 3 (SINGLE_TABLE) puts every variant's columns on
the parent, same NULL problem. Cost of JOINED: one extra JOIN
per funding lookup; cached at the application layer if needed.

---

## R3. Voucher endpoint authentication mechanism

**Question**: FR-007 requires authenticated principals on voucher
endpoints. What mechanism?

**Options**:

1. Service-account JWT (already used by cashu-mint-admin).
2. mTLS at the load balancer.
3. Both, configurable per endpoint.

**Decision**: Option 1 for v1, Option 3 long-term.

**Rationale**: cashu-mint-admin already validates service-account
JWTs via Spring Security. Reusing the same config across voucher
endpoints (a separate `VoucherEndpointSecurityConfig` bean) is
cheap and consistent. mTLS is the right long-term posture for
internal-only services; not needed for v1 because the voucher
endpoint is internal-only by network policy already.

---

## R4. Per-principal rate limiting

**Question**: FR-008 requires per-principal rate limits on
voucher creation/finalization. What implementation?

**Options**:

1. In-process token bucket (e.g. Bucket4j or Caffeine-backed).
2. Distributed (Redis-backed) rate limit.
3. Reverse-proxy-level rate limit (nginx `limit_req`).

**Decision**: Option 1 (in-process, Caffeine-backed token bucket).

**Rationale**: cashu-mint is single-replica at current scale
(per the memory note "customer-wallet is single-replica" applies
here too — cashu-mint hasn't been horizontally scaled). In-process
is sufficient and simple. Profile-specific limits via
`@ConfigurationProperties` (deny-most defaults in `prod`).

Migration path: if/when cashu-mint becomes multi-replica, swap to
Redis without changing the controller contract.

---

## R5. Idempotency-key handling

**Question**: FR-009 requires idempotency on `idempotency_key`.
Where is the store?

**Options**:

1. Reuse the per-replica in-process LRU pattern.
2. Database table `voucher_idempotency_key (key, response_hash,
   status, expires_at)`.

**Decision**: Option 2 (database).

**Rationale**: Voucher idempotency outlasts a JVM restart
(FR-009 mandates the same response on retry — that mandate is
meaningful only if it survives restarts). Database-backed
idempotency keys are the same pattern atomic-purchase already
uses successfully in imani-bridge / gateway-customer (per memory
entry "atomic_purchase.md"). Reuse the pattern.

---

## R6. `merchant_iou` policy enforcement

**Question**: FR-006 requires per-profile policy on whether
`merchant_iou` funding is permitted. How is this expressed?

**Options**:

1. Application property `cashu.mint.voucher.iou-policy =
   ALLOW | DENY` (default `DENY`).
2. Per-merchant policy (some merchants allowed, others not).
3. Tiered policy (per merchant + per profile).

**Decision**: Option 1 for v1.

**Rationale**: Profile-level policy is sufficient at current
scale. Per-merchant policy can be layered on later if needed.
Default `DENY` in `staging` and `prod`; `ALLOW` only in `local`
for development convenience.

Operator alert: every IOU issuance in `staging` (where policy is
`DENY`) generates a structured-log error AND a Micrometer
counter increment. This makes accidental policy drift visible.

---

## R7. NUT-06 advertisement — how to keep voucher OUT

**Question**: FR-010 requires that vouchers NOT appear under the
NUT-06 `nuts` key. How is this enforced?

**Options**:

1. Convention: the NUT-06 info builder hard-codes the list of
   advertised NUTs; vouchers don't appear because nobody adds
   them.
2. Test: a smoke test in `cashu-mint-rest-it` calls the NUT-06
   info endpoint and asserts vouchers are absent.
3. Explicit registry: the info builder iterates over a
   `@StandardNut`-annotated set of task classes; voucher tasks
   carry a different annotation (`@VendorExtension`) so they
   can never be picked up.

**Decision**: Option 1 + Option 2.

**Rationale**: Option 3 is over-engineering for the current
catalogue size. The smoke test (Option 2) is the regression
guard; it asserts on the exact `nuts` key contents and the
expected vendor-extension key.

---

## R8. Migration of in-flight vouchers at deploy time

**Question**: The in-memory `VoucherQuoteRegistry` may have
in-flight vouchers at deploy. How are they handled?

**Options**:

1. Drain: deploy during a quiet window; in-flight vouchers
   simply timeout from the client's perspective.
2. Migrate: serialise the in-memory registry to a temp file
   pre-shutdown; reload into the new durable store post-startup.
3. Tolerate loss: vouchers that don't survive restart are
   considered failed; clients retry with a new quote.

**Decision**: Option 3.

**Rationale**: The whole point of this spec is to make in-memory
state unsafe. A short transition window where in-flight
voucher quotes are lost is acceptable; clients see a clear
"quote expired" response and retry. Option 2 adds complexity
for a one-shot deploy concern; not worth it. A maintenance
window (Option 1) is the operator's call.

---

## R9. Hibernate Envers vs. dedicated audit table

**Question**: Same question as spec 001 R10 — Envers or
hand-rolled audit?

**Decision**: Envers, mirroring spec 001's decision.

**Rationale**: Same as spec 001 R10. `voucher_quote_aud` +
`voucher_funding_aud` + `voucher_issuance_aud` are auto-created.
Spec-001 already establishes Envers as the cashu-mint audit
mechanism.

---

## R10. Voucher response cache for FR-009 idempotency

**Question**: How does the idempotency cache return the original
response shape for a repeated `idempotency_key`?

**Options**:

1. Store the serialised response JSON + status code in the
   idempotency row.
2. Re-execute the request against the durable voucher record
   (which would itself be idempotent because the quote is
   already created).

**Decision**: Option 1.

**Rationale**: Atomic, fast, and guaranteed identical to the
first response. Option 2 risks subtle drift (e.g. a generated
timestamp differs on the second call). Same idiom as spec 001's
`signatures_json` and spec 002's `melt_response_cache`.

---

## Open items deferred to Phase 2 (/speckit.tasks)

- The cross-repo `imani-bridge` change to pass `funding_ref` /
  `idempotency_key` from `WalletPluginAdapter.quoteVoucherMint`
  to the mint — needs a coordinated PR.
- Operator dashboard layout for the new `merchant_iou` liability
  class.
- The voucher-creation REST API surface — does it stay where it
  is, or move under `/admin/voucher/`? (Probably stays; auth
  hardening is enough.)

No NEEDS CLARIFICATION items remain.
