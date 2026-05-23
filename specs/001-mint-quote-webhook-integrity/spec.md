# Feature Specification: Mint Quote Amount Binding and Webhook Integrity

**Feature Branch**: `001-mint-quote-webhook-integrity`
**Created**: 2026-05-22
**Status**: Draft
**Input**: Backend Token Integrity Review (2026-05-22) — findings "Critical: Paid mint quotes are not bound to minted output amount or consumed" and "High: Webhooks can mark quotes paid without durable amount/unit binding".
**Source Repository**: `cashu-mint`
**Related Sibling Specs**:
- imani-bridge — webhook issuer hardening (tracked separately)
- cashu-mint spec `003-voucher-quote-durability` — shares the durable quote-record entity for the voucher path
- payment-adapter — `Gateway.getAmount(quoteId)` and `PhoenixdGateway.checkPaymentStatus` (cross-check source)

## Constitution Alignment

This feature is governed by the cashu-mint Constitution v1.1.0
(`.specify/memory/constitution.md`). Every requirement below
traces to one or more principles.

- **Principle I — Token Integrity (NON-NEGOTIABLE)**: "no silent
  inflation" (FR-001, FR-002, FR-003), "durable financial state"
  (FR-004, FR-008, FR-011), "amount-bound webhooks" (FR-005,
  FR-006), "`long` arithmetic" (FR-009), "operator-visible
  alerts" (SC-005, FR-007).
- **Principle II — Protocol Compliance (Cashu NUTs)**: this
  spec is the **mint-side** of NUT-04 ([Mint tokens](https://github.com/cashubtc/nuts/blob/main/04.md)),
  with downstream effects on NUT-06 ([Mint info](https://github.com/cashubtc/nuts/blob/main/06.md))
  and idempotent retry semantics governed by NUT-19
  ([Cached responses](https://github.com/cashubtc/nuts/blob/main/19.md)).
  The signed-quote variant in NUT-20 ([Signed mint quote](https://github.com/cashubtc/nuts/blob/main/20.md))
  is in-scope where supported.
- **Principle III — Clean Architecture**: durable state lives in
  `cashu-mint-protocol` ports; persistence adapters live in
  infrastructure. REST controllers do not move quote state.
- **Principle IV — Testing Discipline**: regression tests in
  Section "Suggested Regression Tests" of the source review are
  in-scope and MUST be unit + integration tests using
  Testcontainers (no H2 for the write path).
- **Principle VI — Secure Coding**: webhook signature
  validation MUST be mandatory in non-local profiles; startup
  fails when the secret is missing.

## Background and Problem Statement

In the regular NUT-04 mint flow today:

1. `MintTask` checks whether a quote is paid (via
   `Gateway.checkPaymentStatus(quoteId)`), but for non-voucher
   quotes it does **not** compare `sum(blindedMessages.amount)`
   against the quote's authorised amount
   (`cashu-mint-protocol/.../tasks/MintTask.java:102`).
2. After signing, the quote is **not** marked consumed; the
   gateway continues to report `PAID` for that quote, so the
   same paid quote remains a reusable authorisation signal
   (`MintTask.java:119`, `MintTask.java:138`,
   `MintTask.java:157`).
3. Webhooks (both in `imani-bridge` and `cashu-mint`) mark
   quotes paid based on `quoteId` alone. The idempotency key
   is `paymentMethod:quoteId`; it does not include provider
   event id, amount, currency/unit, or provider payment id
   (`cashu-mint-webhook/.../QuoteStatusUpdater.java:133` and
   `:157`). Signature validation is skipped when the shared
   secret is blank
   (`cashu-mint-webhook/.../WebhookSignatureValidator.java:42`).
4. Cached "paid" state in `QuoteStatusService` /
   `QuoteStatusUpdater` can diverge from durable quote state.

Together these gaps allow three loss-of-integrity scenarios:

- **Silent over-mint**: a user pays a small quote and submits
  larger blinded outputs.
- **Repeated mint**: a user reuses the same paid quote with
  fresh blinded outputs after a successful issuance.
- **Wrong-quote / wrong-unit paid transition**: a malformed,
  replayed, or compromised webhook event marks the wrong quote
  paid (or marks a quote paid against the wrong amount/unit).

The cashu-mint codebase is the authoritative point at which
these invariants must hold, regardless of what gateway, webhook
source, or payment adapter is in front of it. This spec covers
the **mint-side** contract; the matching changes in
`imani-bridge` are tracked separately.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Quote amount binds the issuance (Priority: P1)

A mint operator MUST be able to trust that the sum of issued
blinded outputs for any non-voucher mint quote is exactly equal
to the amount the customer paid against that quote, and that
each paid quote authorises issuance at most once.

**Why this priority**: This is the single biggest source of
silent inflation risk identified in the review. Until it is
fixed, every paid mint quote in production is a potential
reusable authorisation token. Direct violation of Constitution
Principle I.

**Independent Test**: A black-box integration test that drives
the public NUT-04 mint endpoint with crafted blinded output
sets (under, over, exact, repeated) and asserts that only the
exact-and-first-time case succeeds; all other cases are
rejected with a typed error and zero `BlindSignature`s issued.

**Acceptance Scenarios**:

1. **Given** a NUT-04 quote authorised for 10 sats and marked
   `PAID`, **When** the client requests blinded outputs summing
   to 9 sats, **Then** the request is rejected with an explicit
   `amount_mismatch` error and no signatures are issued.
2. **Given** the same quote, **When** the client requests
   blinded outputs summing to 11 sats, **Then** the request is
   rejected with `amount_mismatch` and no signatures are
   issued.
3. **Given** the same quote, **When** the client requests
   blinded outputs summing to exactly 10 sats, **Then**
   signatures are issued **and** the quote transitions
   atomically from `PAID` → `ISSUING` → `ISSUED` in a single
   database transaction.
4. **Given** an already-`ISSUED` quote, **When** the client
   retries with the **same** blinded outputs, **Then** the
   mint returns the previously signed promises (idempotent
   replay per NUT-19) and does not produce a second issuance
   record.
5. **Given** an already-`ISSUED` quote, **When** the client
   retries with **different** blinded outputs (same sum or
   otherwise), **Then** the request is rejected with
   `quote_already_issued`.

---

### User Story 2 — Webhook-driven `PENDING → PAID` is amount/unit/event bound (Priority: P1)

When the mint or its gateway receives a payment webhook, the
`PENDING → PAID` transition for the referenced quote MUST be
conditional on the webhook's amount, unit, payment method, and
provider event id matching the original quote, and MUST be
atomic and idempotent on the durable quote record (not on an
in-process cache).

**Why this priority**: A spoofed, replayed, or misrouted
webhook today can mark a quote paid against the wrong amount
or unit, after which Story 1 alone is not sufficient to
recover — the quote considered "paid" would not match the
actual deposit. Direct violation of Constitution Principle I
("amount-bound webhooks") and Principle VI (mandatory
signature validation).

**Independent Test**: Drive `cashu-mint-webhook`'s
`PaymentWebhookController` with a sequence of crafted
notifications (correct, amount-mismatch, unit-mismatch,
replayed, unsigned-with-required-secret) and assert that only
the correct, single, first-arrival notification advances quote
state to `PAID`. Verify a `WebhookEvent` row is persisted with
the corresponding outcome in every case.

**Acceptance Scenarios**:

1. **Given** a quote authorised for 10 sats EUR-LN, **When** a
   webhook arrives reporting 9 sats EUR-LN for that quote,
   **Then** the quote stays `PENDING` and the event is
   persisted with `outcome = amount_mismatch`.
2. **Given** the same quote, **When** a webhook arrives
   reporting 10 USD-LN for that quote, **Then** the quote
   stays `PENDING` (unit mismatch) and the event is persisted
   with `outcome = unit_mismatch`.
3. **Given** the same quote, **When** a correctly-signed
   webhook with the exact expected amount/unit/method arrives,
   **Then** the quote transitions `PENDING → PAID` exactly
   once and a `WebhookEvent` record is persisted with
   `provider_event_id` and `outcome = accepted`.
4. **Given** the same webhook, **When** it is replayed
   (same `provider_event_id`), **Then** the second delivery
   is acknowledged as a duplicate and does not re-transition
   the quote (`outcome = duplicate`).
5. **Given** the same `provider_event_id` paired with a
   **different** amount or quote, **When** delivered, **Then**
   the second delivery is rejected as a tampering signal and
   surfaced for operator review (`outcome = tamper`).
6. **Given** any non-local Spring profile (`staging`, `prod`),
   **When** the mint starts without a configured webhook
   secret, **Then** startup fails with a clear error message
   identifying the missing property.
7. **Given** a configured secret, **When** an unsigned or
   invalid-signature webhook arrives, **Then** it is rejected
   with HTTP 401 before any state mutation and the event is
   persisted with `outcome = unsigned_rejected`.

---

### User Story 3 — Idempotent retry after partial issuance (Priority: P2)

A client that loses its response between blinded-output
submission and signature receipt MUST be able to retry safely
and get the same signatures back, without being able to
escalate that retry into a second issuance.

**Why this priority**: Without this, integrators are pushed to
design around it (caching responses locally, swallowing
errors), which historically reintroduces the very inflation
vector Story 1 closes. This is the natural correctness
counterpart to Story 1, and aligns with NUT-19's cached-
responses contract.

**Independent Test**: Two consecutive identical NUT-04 mint
calls against the same quote produce the same signed promises
in both responses and exactly one `IssuanceRecord` row.

**Acceptance Scenarios**:

1. **Given** a `PAID` quote, **When** the client submits the
   same blinded outputs twice, **Then** both responses contain
   the same signatures and only one issuance is recorded.
2. **Given** a `PAID` quote with `ISSUING` state, **When** the
   second concurrent request arrives, **Then** it blocks on
   the first and either returns the same signatures or a clean
   `issuance_in_progress` error.

---

### Edge Cases

- Two concurrent mint requests for the same paid quote with
  the same blinded outputs (must collapse to one issuance; the
  second sees idempotent replay).
- Two concurrent mint requests with **different** outputs
  (only one issues; the other receives `quote_already_issued`
  or `issuance_in_progress`).
- Webhook arrives before the quote row is persisted (must
  store the event as an orphan and reconcile on quote
  creation; MUST NOT create the quote from the webhook).
- Webhook arrives after the quote has expired (must not
  transition; record the event with `outcome = expired`).
- Webhook arrives for a quote that has already been `ISSUED`
  (must not regress state; record `outcome = noop`).
- Provider sends two events with the same id but different
  bodies (must be flagged as `outcome = tamper` and surfaced).
- `Gateway.getAmount(quoteId)` returns a value that disagrees
  with the quote's stored authorised amount (must not silently
  overwrite; surface the discrepancy and refuse to advance
  state).
- Quote `amount` is `Long.MAX_VALUE - 1` and outputs sum to
  `Long.MAX_VALUE` (must reject as `amount_mismatch` without
  overflow).
- All amount arithmetic uses `long`; no `int` /
  `Stream.mapToInt(...)` over amounts (Constitution
  Principle I).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: For every non-voucher mint quote, the mint MUST
  require `sum(outputs.amount) == quote.amount`. Inequality
  (in either direction) MUST be rejected with a typed
  `amount_mismatch` error and zero signatures issued.
  [NUT-04, Constitution I]
- **FR-002**: Each non-voucher mint quote MUST be issuable at
  most once. After signing, the quote MUST transition
  atomically from `PAID` to `ISSUING` to `ISSUED` in a single
  durable transaction (or compare-and-set equivalent) keyed by
  `quote_id`. [Constitution I — durable financial state]
- **FR-003**: A retry against an `ISSUED` quote with the
  **same** blinded outputs MUST return the previously signed
  promises; a retry with **different** outputs MUST be
  rejected with `quote_already_issued`. [NUT-19]
- **FR-004**: The mint MUST persist, for every quote, at
  minimum: `quote_id`, `amount`, `unit`, `mint_url`,
  `payment_method`, `invoice_id`, `lifecycle_state`,
  `created_at`, `updated_at`. The lifecycle state MUST be the
  source of truth; no in-process cache may shadow it for
  write decisions. [Constitution I]
- **FR-005**: Webhook-driven `PENDING → PAID` transitions
  MUST require all of: matching `quote_id`, matching
  `amount`, matching `unit`, matching `payment_method`, and a
  previously-unseen `provider_event_id`. Any mismatch MUST
  leave the quote in `PENDING` and record the event with a
  mismatch outcome. [Constitution I — amount-bound webhooks]
- **FR-006**: Webhook idempotency MUST be keyed by
  `(provider, provider_event_id)` (not by
  `paymentMethod:quoteId`). A duplicate `provider_event_id`
  MUST be acknowledged without state mutation. A repeated
  `provider_event_id` paired with a different amount/quote
  MUST be rejected as a tamper signal.
- **FR-007**: Webhook signature validation MUST be mandatory
  in all non-local Spring profiles. The application MUST fail
  to start when a profile that requires signatures is missing
  its shared secret. [Constitution VI]
- **FR-008**: Every webhook event MUST be persisted with
  `provider_event_id`, `quote_id`, `amount`, `unit`,
  `payment_method`, `signature_digest`, `received_at`, and an
  outcome (`accepted`, `amount_mismatch`, `unit_mismatch`,
  `duplicate`, `tamper`, `unsigned_rejected`, `expired`,
  `noop`). This record MUST survive process restart.
- **FR-009**: All financial-amount arithmetic in the mint and
  webhook validation paths MUST use `long` (or a decimal-safe
  type). `int` / `Stream.mapToInt(...)` over amounts MUST be
  removed from validation paths. [Constitution I]
- **FR-010**: When the underlying payment adapter exposes
  `getAmount(quoteId)`, the mint MUST cross-check that
  against the locally-persisted quote amount on each `PAID`
  transition; a discrepancy MUST block the transition and
  surface an operator alert.
- **FR-011**: A successful `MintTask` MUST emit a single
  durable `IssuanceRecord` keyed by `(quote_id)`; re-running
  `MintTask` for the same quote MUST NOT create a second
  ledger entry. [Constitution I — durable financial state]
- **FR-012**: Errors surfaced by FR-001 through FR-011 MUST
  be distinct, machine-readable error codes (not generic
  400s) so integrators can react correctly.
- **FR-013**: The NUT-06 info endpoint MUST advertise
  per-method/unit mint limits and supported features
  accurately. Advertised support implies a passing
  integration test against the linked spec revision.
  [Constitution II]
- **FR-014**: All Javadoc on `MintTask`, `MintQuoteTask`, and
  the webhook handlers MUST link to the NUT spec section the
  code implements, pinned to a specific commit hash on
  [github.com/cashubtc/nuts](https://github.com/cashubtc/nuts).
  [Constitution II]

### Key Entities

- **MintQuote**: durable record of a quote authorisation.
  Attributes: `quote_id`, `amount` (long), `unit`,
  `mint_url`, `payment_method`, `invoice_id`,
  `lifecycle_state` (`UNPAID | PENDING | PAID | ISSUING |
  ISSUED | EXPIRED | FAILED`), `created_at`, `updated_at`,
  plus a content hash of the original quote request to detect
  post-creation tampering. Hibernate Envers audit-tracked.
- **IssuanceRecord**: append-only ledger entry keyed by
  `(quote_id)` that captures the issued blinded-output hash
  set and the resulting signatures. Used for idempotent
  replay (Story 3).
- **WebhookEvent**: append-only record keyed by
  `(provider, provider_event_id)` with `quote_id`, `amount`
  (long), `unit`, `payment_method`, `signature_digest`,
  `received_at`, `outcome`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of NUT-04 mint requests that violate
  `sum(outputs) == quote.amount` are rejected with
  `amount_mismatch` and produce zero signatures.
  (Verified by integration tests across under-/over-/exact-
  mint and concurrent-same-quote scenarios.)
- **SC-002**: For any single quote across its full lifecycle,
  the cashu-mint database contains exactly one row in
  `IssuanceRecord`. (Verified by stress test issuing N
  concurrent identical mint requests against one quote.)
- **SC-003**: 0 paid-quote replays succeed in production logs
  after rollout. (Measured by counting `quote_already_issued`
  rejections vs. successful re-issuances; the latter must be
  0.)
- **SC-004**: 100% of webhook `PENDING → PAID` transitions in
  production have a backing `WebhookEvent` row with
  `outcome = accepted` and matching amount/unit/method/event-
  id. (Verified by a daily reconciliation job.)
- **SC-005**: Mint startup fails in `staging` and `prod`
  profiles when the webhook secret is unset. (Verified by a
  startup-failure test.)
- **SC-006**: All financial-amount fields in the validation
  path use `long`. (Verified by a static-analysis or compile-
  time check; no `int` / `mapToInt` regressions land.)
- **SC-007**: NUT-06 advertised support matches the integration-
  test set 1:1; no NUT is advertised without a passing test.

## Assumptions

- The fix is **mint-side**. The matching webhook-issuer
  changes in `imani-bridge` (`PaymentWebhookController`,
  `QuoteStatusService`) are tracked in a sister spec under
  that repo and assumed to be delivered in lock-step with
  this one.
- The voucher mint quote path (`VoucherMintQuoteTask` /
  `VoucherQuoteRegistry`) is **out of scope** here — it is
  covered by spec `003-voucher-quote-durability` of this
  batch. The two specs share the durable-quote-record entity
  but the voucher path has a different funding model.
- The fix introduces a durable store (existing JPA module is
  the most natural home, or a new `cashu-mint-quote-jpa`
  module if the protocol module should remain free of JPA);
  no new database engine is required.
- A migration step will be needed to materialise existing
  in-flight quotes into the new durable representation.
  Migration plan is out of scope for this spec but will be
  tracked under planning.
- Payment-adapter `Gateway.getAmount(quoteId)` is reliable
  enough to use as a cross-check, but its value MUST NOT be
  the sole source of truth for `quote.amount`.
- Existing webhook clients (gateway, payment-adapter) will
  not be reconfigured by this change; the mint is responsible
  for accepting only well-formed, signed, amount-matched
  events and rejecting everything else cleanly.
- Hibernate Envers (already a vault dependency, available in
  the mint stack) is the natural mechanism for the audit
  trail of `MintQuote.lifecycle_state` transitions.
