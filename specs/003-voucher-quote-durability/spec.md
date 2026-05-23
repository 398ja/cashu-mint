# Feature Specification: Voucher Quote Durability and Funding-Source Binding

**Feature Branch**: `003-voucher-quote-durability`
**Created**: 2026-05-22
**Status**: Draft
**Input**: Backend Token Integrity Review (2026-05-22) — finding "Critical: Voucher mint quotes intentionally skip payment checks".
**Source Repository**: `cashu-mint`
**Code Touch Points (mint-side)**:
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/protocol/tasks/VoucherMintQuoteTask.java:64`
- `VoucherMintQuoteTask.java:77`
- `VoucherMintQuoteTask.java:84`
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/protocol/tasks/MintTask.java:108` (skip-payment-check branch for voucher quotes)
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/protocol/tasks/VoucherQuoteRegistry.java:26`
- `VoucherQuoteRegistry.java:52`

**Cross-Repo Sibling Spec (out of scope here)**:
- `imani-bridge` — gateway-side voucher orchestration in
  `WalletPluginAdapter` (`quoteVoucherMint`,
  `mintWithQuoteSkipPaymentCheck`, finalization). The
  matching gateway-side hardening is tracked separately;
  this spec assumes that work lands in lock-step.

## Constitution Alignment

This feature is governed by the cashu-mint Constitution v1.1.0
(`.specify/memory/constitution.md`). Every requirement below
traces to one or more principles.

- **Principle I — Token Integrity (NON-NEGOTIABLE)**: vouchers
  produce spendable Cashu proofs. The "skip-payment-check"
  branch creates value without a durable funding record,
  directly violating "no silent inflation" and "durable
  financial state". This spec exists to close that gap.
- **Principle II — Protocol Compliance (Cashu NUTs)**:
  vouchers are a non-standard extension on top of NUT-04
  ([Mint tokens](https://github.com/cashubtc/nuts/blob/main/04.md)).
  Per the constitution, non-standard paths MUST be clearly
  separated, gated, and MUST NOT be advertised under the
  `nuts` key of NUT-06. This spec enforces that contract.
- **Principle III — Clean Architecture**: voucher state moves
  out of `VoucherQuoteRegistry` (in-memory infrastructure
  detail) into a durable port owned by the protocol module.
- **Principle IV — Testing Discipline**: a restart test for
  voucher quote classification and face value is mandatory
  (called out explicitly by the source review under
  "Suggested Regression Tests").
- **Principle VI — Secure Coding**: voucher quote creation
  and finalization endpoints MUST be authenticated; rate-
  limited; idempotent.

## Background and Problem Statement

The gateway direct-voucher flow calls `quoteVoucherMint`,
stores the pending voucher as `isVoucherQuote`, and later
calls `mintWithQuoteSkipPaymentCheck`. On the mint side:

- `VoucherQuoteRegistry` classifies a quote as a voucher
  quote and persists the classification **only in memory**
  (`VoucherQuoteRegistry.java:26`,
  `VoucherQuoteRegistry.java:52`).
- `VoucherMintQuoteTask` records the face value
  (`VoucherMintQuoteTask.java:64`–`:84`) — also in memory.
- `MintTask` skips payment verification for voucher quotes
  (`MintTask.java:108`).

The mint *does* validate the voucher output total against the
registered face value (good), but:

- The backing payment / funding check is **intentionally
  bypassed**. There is no durable record establishing that
  the issued voucher is funded by a settled customer payment
  or a durable merchant debit.
- Classification and face value live in process memory; a
  restart loses them. An in-flight voucher quote can become
  unclassifiable, or — worse — re-classifiable.
- Cashu proofs minted via this path are indistinguishable
  from regular mint proofs once issued. The mint cannot
  later audit which proofs originated from a fully-backed
  customer payment versus a (intended) merchant IOU.

If this path is reachable for customer-paid or public
voucher creation, it can mint spendable Cashu proofs
without a corresponding settled payment or durable
merchant-funded debit — i.e., it inflates the mint's
liability with no matching asset record.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Every voucher proof is backed by a durable funding record (Priority: P1)

A mint operator MUST be able to point at any issued voucher
proof and trace it back to a durable funding record (a
settled customer payment, a merchant-funded debit, or an
explicitly-recorded IOU). No voucher is issued without that
record.

**Why this priority**: This is the existential token-
integrity concern. Today, vouchers can issue spendable
Cashu sats with no asset side on the mint's books. Direct
violation of Constitution Principle I.

**Independent Test**: An integration test that creates a
voucher quote with no associated payment / debit / IOU
record and asserts the mint refuses to advance the quote
to `ISSUED`, returning a typed `funding_required` error.

**Acceptance Scenarios**:

1. **Given** a voucher quote requested with no funding
   source, **When** the client attempts to mint, **Then**
   the mint rejects with `funding_required` and zero
   signatures are issued.
2. **Given** a voucher quote with a settled customer payment
   record (via payment-adapter), **When** the client mints,
   **Then** signatures are issued and the durable record
   shows `funding_source = customer_payment` linked to a
   `provider_event_id` and `amount`.
3. **Given** a voucher quote with a durable merchant-funded
   debit record, **When** the client mints, **Then**
   signatures are issued and the durable record shows
   `funding_source = merchant_debit` linked to a merchant
   identity and amount.
4. **Given** a voucher quote that is explicitly modelled as a
   merchant IOU (operator decision), **When** the client
   mints, **Then** signatures are issued and the durable
   record shows `funding_source = merchant_iou` with an
   IOU id. The IOU MUST appear as a distinct liability
   class to operators (not silently mingled with backed
   vouchers).
5. **Given** any voucher path, **When** the mint attempts to
   advertise voucher support via NUT-06, **Then** the
   advertisement MUST NOT appear under the `nuts` key
   (voucher is non-standard per Constitution II); it MAY
   appear under a vendor-extension key.

---

### User Story 2 — Voucher quote state survives restart (Priority: P1)

A voucher quote's classification, face value, requested
amount, funding source, merchant identity, and lifecycle
state MUST survive a mint process restart. The
`VoucherQuoteRegistry` in-memory map MUST NOT be the source
of truth for write decisions.

**Why this priority**: Today, a restart can lose voucher
classification, which means subsequent mint requests against
the same quote could either be silently mis-classified (no
voucher validation applied) or rejected after the customer
has paid (deflation). Direct violation of Constitution
Principle I ("durable financial state").

**Independent Test**: An integration test that creates a
voucher quote, restarts the mint container via
Testcontainers, then attempts to mint against the quote and
asserts classification and face value match the pre-restart
values.

**Acceptance Scenarios**:

1. **Given** a voucher quote created and funded before
   restart, **When** the mint restarts and the client
   resumes the mint call, **Then** the quote retains its
   `voucher_quote` classification, its `face_value`, and its
   `funding_source` link.
2. **Given** the same quote, **When** queried after restart
   by `quote_id`, **Then** the mint returns the same
   lifecycle state it would have returned pre-restart.
3. **Given** an in-memory cache (read accelerator only),
   **When** it is cold after restart, **Then** the durable
   store rehydrates it lazily on first read.

---

### User Story 3 — Voucher quote creation/finalization is authenticated, rate-limited, and idempotent (Priority: P2)

The voucher quote creation and finalization endpoints MUST
require service-account or merchant authentication, MUST be
rate-limited per principal, and MUST be idempotent under
client retry.

**Why this priority**: Without this, a network-level
adversary or buggy client can fan out voucher quote creation
and (if the funding gate is bypassed via Story 1's loophole)
extract value. Even after Story 1 closes the funding gate,
auth and rate-limiting are required defence-in-depth per
Constitution VI.

**Independent Test**: A request without credentials returns
401; identical retries with the same idempotency key produce
the same response and only one durable record.

**Acceptance Scenarios**:

1. **Given** an unauthenticated client, **When** it calls
   the voucher quote endpoint, **Then** it receives 401
   before any state is created.
2. **Given** an authenticated principal that exceeds the
   per-principal rate limit, **When** it calls the endpoint,
   **Then** it receives 429 and no state is created.
3. **Given** an authenticated principal that submits the
   same `idempotency_key` twice, **When** the second call
   arrives, **Then** the response is identical to the first
   and only one durable voucher quote record exists.

---

### Edge Cases

- Voucher quote is created with a settled funding record,
  but the funding record is later disputed/refunded
  out-of-band. The mint MUST NOT void already-issued
  voucher proofs (that would break the bearer property of
  Cashu tokens) but MUST surface the dispute as an
  operator-visible liability event and flag the funding
  record.
- Voucher quote is created with a merchant IOU funding
  source. If the operator's policy is that IOUs are not
  permitted in `prod`, the mint MUST reject creation at
  request time, not at mint time, and surface a typed
  error.
- Two concurrent mint requests against the same paid voucher
  quote with the same blinded outputs (must collapse to one
  issuance; the second sees idempotent replay) — same
  guarantee as spec 001.
- Voucher quote with face value 0 or negative (must reject
  at creation).
- Voucher quote face value mismatch with sum of requested
  blinded outputs (must reject at mint time; existing
  validation is retained).
- Voucher quote `funding_source` is missing while the
  configured policy requires one (must reject at mint time
  with `funding_required`).
- Voucher quote `funding_source` references a non-existent
  payment id (must reject with `funding_not_found`).
- All amounts use `long`; no `int` /
  `Stream.mapToInt(...)` over amounts.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Voucher quote classification, face value,
  charged amount, fee, merchant/customer identity, funding
  source, and lifecycle state MUST be persisted in a
  durable store. The `VoucherQuoteRegistry` in-memory map
  MUST be relegated to a read-through cache only.
  [Constitution I, III]
- **FR-002**: The mint MUST NOT issue voucher proofs unless
  one of the following is true:
  - a settled customer payment record exists with matching
    amount, unit, and `payment_method`; or
  - a durable merchant-funded debit record exists with
    matching amount and merchant identity; or
  - an explicit `funding_source = merchant_iou` record
    exists AND the deployment policy permits IOUs in the
    current Spring profile.
  Any other state MUST yield a typed `funding_required`
  error. [Constitution I — no silent inflation]
- **FR-003**: Voucher quote types MUST NOT be inferred from
  in-memory state at mint time. The mint MUST read the
  voucher quote type from the durable record keyed by
  `quote_id`.
- **FR-004**: The durable voucher quote record MUST capture
  at minimum: `quote_id`, `voucher_type`, `face_value`
  (long), `charged_amount` (long), `fee` (long), `unit`,
  `merchant_id` (nullable), `customer_id` (nullable),
  `funding_source` (`customer_payment | merchant_debit |
  merchant_iou`), `funding_ref` (provider event id /
  merchant debit id / iou id), `lifecycle_state`,
  `created_at`, `updated_at`. Hibernate Envers audit-
  tracked.
- **FR-005**: Issued voucher proofs MUST be traceable from
  the `IssuanceRecord` (spec 001) back to the funding
  record via the durable voucher quote record. A query
  "given proof X, return its funding source" MUST be
  answerable by an audit endpoint.
- **FR-006**: If the deployment policy disallows
  `merchant_iou` in the current Spring profile, voucher
  creation requesting IOU funding MUST be rejected at
  creation time, not at mint time.
- **FR-007**: Voucher quote creation and finalization
  endpoints MUST require authentication (service-account
  or merchant principal). Unauthenticated calls MUST be
  rejected before any state is created. [Constitution VI]
- **FR-008**: Voucher quote creation endpoints MUST be
  rate-limited per principal; limits MUST be configurable
  per profile and MUST default to a deny-most posture in
  `prod`.
- **FR-009**: Voucher quote creation and finalization MUST
  be idempotent on `idempotency_key`. Repeated calls with
  the same key MUST return the original response; calls
  with the same key but a different payload MUST be
  rejected as a tamper signal.
- **FR-010**: Voucher support MUST NOT be advertised under
  the NUT-06 `nuts` key. It MAY be advertised under a
  vendor-extension key with a link to internal
  documentation. [Constitution II]
- **FR-011**: All voucher amount arithmetic MUST use `long`.
  `int` / `Stream.mapToInt(...)` over amounts MUST be
  removed. [Constitution I]
- **FR-012**: A failed `voucherService.importVoucher`
  downstream MUST NOT result in a successful voucher
  issuance response. Persistence failure either rolls back
  issuance (preferred) or writes a durable outbox record
  before returning, so reconciliation can complete
  issuance exactly once. (Cross-repo coordination with
  imani-bridge.)
- **FR-013**: Javadoc on `VoucherMintQuoteTask` and the
  voucher branch of `MintTask` MUST explicitly note that
  vouchers are a non-standard extension on top of
  [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md),
  pinned to a specific commit hash. [Constitution II]
- **FR-014**: Operator-visible alerts MUST fire on:
  voucher issuance with `funding_source = merchant_iou`
  in any profile where IOUs are permitted; voucher
  issuance where the funding record cannot be located
  during reconciliation; voucher quote rate-limit
  breaches.

### Key Entities

- **VoucherQuote**: durable record keyed by `quote_id` with
  fields above (FR-004). The vouching extension of the
  generic `MintQuote` from spec 001; the two share a
  lifecycle state machine but voucher quotes carry the
  voucher-specific fields.
- **VoucherFunding**: durable record of the asset side
  backing a voucher. Variants: `CustomerPaymentFunding`
  (links to `WebhookEvent.provider_event_id`),
  `MerchantDebitFunding` (links to merchant ledger debit
  id), `MerchantIouFunding` (links to an IOU id).
- **VoucherIssuance**: append-only ledger row linking
  `voucher_quote_id` → `funding_id` → `issuance_id` (the
  spec-001 `IssuanceRecord`). Enables proof-to-funding
  audit (FR-005).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of issued voucher proofs in production
  trace to a durable `VoucherFunding` record via
  `VoucherIssuance`. (Daily reconciliation job; zero
  orphans tolerated.)
- **SC-002**: 0 voucher quote classifications are lost
  across mint restarts. (Restart test in
  `cashu-mint-rest-it` with Testcontainers.)
- **SC-003**: 0 voucher quote endpoints accept
  unauthenticated requests in `staging` and `prod`
  profiles. (Smoke test in CI.)
- **SC-004**: NUT-06 advertised support does NOT include
  the voucher extension under the `nuts` key. (Smoke
  test in CI.)
- **SC-005**: All voucher amount fields use `long` in
  code; static-analysis check passes.
- **SC-006**: Operator dashboards show
  `merchant_iou`-funded voucher liabilities as a distinct
  class from customer-paid voucher liabilities; the
  combined liability total reconciles to the asset side
  daily.

## Assumptions

- The gateway-side hardening in `imani-bridge`
  (`WalletPluginAdapter.quoteVoucherMint`,
  `mintWithQuoteSkipPaymentCheck`, finalization persistence)
  is tracked in a sister spec and lands in lock-step. This
  spec assumes that work delivers a clean way for the
  gateway to attach a `funding_ref` to the voucher quote
  at creation time.
- Hibernate Envers (a known dependency in the stack) is
  the natural audit mechanism for `VoucherQuote` and
  `VoucherIssuance` state transitions.
- Policy on whether `merchant_iou` is permitted is
  configurable per profile. Default in `prod` is "not
  permitted" unless an operator explicitly enables it.
- The spec-001 `MintQuote` durable record is extended
  (rather than duplicated) to carry voucher fields. If
  the implementation chooses to keep separate tables,
  the audit query in FR-005 MUST still resolve in one
  hop.
- Migration of existing in-memory voucher quotes at
  deploy time will be handled in planning; a maintenance
  window or draining strategy is acceptable.
- Voucher creation endpoints currently exposed by
  `imani-bridge` map cleanly to the new auth + rate-limit
  contract; if not, additional gateway-side adjustments
  are tracked separately.
