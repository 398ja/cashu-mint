# Feature Specification: Melt Path Burn-First Ordering and Burn-Amount Check

**Feature Branch**: `002-melt-burn-ordering`
**Created**: 2026-05-22
**Status**: Draft
**Input**: Backend Token Integrity Review (2026-05-22) — finding "High: Melt payment can be made before durable proof invalidation, and the burn amount check is suspicious".
**Source Repository**: `cashu-mint`
**Code Touch Points**:
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/protocol/tasks/MeltTask.java:99`
- `MeltTask.java:104`
- `MeltTask.java:112`
- `MeltTask.java:118`
- `MeltTask.java:127`
- `Gateway.pay(...)` in payment-adapter
- proof state store in `cashu-vault`

## Constitution Alignment

This feature is governed by the cashu-mint Constitution v1.1.0
(`.specify/memory/constitution.md`). Every requirement below
traces to one or more principles.

- **Principle I — Token Integrity (NON-NEGOTIABLE)**: "no
  silent deflation" (FR-001, FR-002), "durable financial
  state" (FR-003, FR-004, FR-006), "`long` arithmetic"
  (FR-009), "operator-visible alerts" (FR-007, FR-008,
  FR-011).
- **Principle II — Protocol Compliance (Cashu NUTs)**: this
  spec is the **mint-side** of NUT-05 ([Melt tokens](https://github.com/cashubtc/nuts/blob/main/05.md))
  with optional behaviour governed by NUT-08
  ([Lightning fee return](https://github.com/cashubtc/nuts/blob/main/08.md)).
- **Principle III — Clean Architecture**: saga state lives
  behind a port owned by the protocol module; the
  payment-adapter gateway and proof store remain
  infrastructure adapters.
- **Principle IV — Testing Discipline**: every saga
  transition MUST be exercised by an integration test
  (Testcontainers + provider-mock), per the constitution's
  rejection of mocked approximations for payment-status
  transitions.
- **Principle VI — Secure Coding**: payment provider responses
  MUST be parsed strictly; ambiguous / missing fields treated
  as failure, never as silent success.

## Background and Problem Statement

The current `MeltTask` flow has two related correctness gaps:

1. **Burn-amount check is suspicious.** It computes
   `totalAmount` as proof sum plus fees/reserve, then
   compares that *total* against `amount + fee_reserve`
   (`MeltTask.java:104`). Depending on how
   `postMeltRequest.getFees` and `calculated_fee_reserve`
   are derived, this comparison can be satisfied even when
   `sum(proofs.amount) < invoiceAmount`. The check should be
   expressed directly as
   `sum(proofs.amount) >= invoiceAmount + exactFeeReserve`
   using `long` arithmetic. The current expression is opaque
   and the spread between `getFees` and `calculated_fee_reserve`
   is non-obvious.
2. **Payment-before-burn ordering.** The task calls
   `gateway.pay(quoteId)` (`MeltTask.java:112`) before
   marking the input proofs pending/spent
   (`MeltTask.java:118`–`:127`) and invalidating them. If the
   external payment succeeds and the subsequent invalidation
   fails (DB hiccup, process death, vault-call timeout),
   value has left the mint while the proofs that should have
   funded it remain spendable — a deflation/loss event with
   hard-to-reconcile state.

Together these mean:

- A melt request with input proofs worth *less* than the
  invoice plus fee reserve can pass validation depending on
  arithmetic semantics (silent under-burn / mint reserve
  leakage).
- A melt request that passes validation can result in the
  mint paying an external invoice while the input proofs
  remain unburned (loss/inflation downstream).
- Recovery state after partial failure is implicit and
  process-local.

This spec covers the mint-side fix.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Under-funded melt is rejected before any external payment (Priority: P1)

A mint operator MUST be able to trust that no external
payment is attempted unless the input proofs strictly cover
the invoice amount plus the exact fee reserve.

**Why this priority**: This closes the silent-deflation /
reserve-leakage door that the review flagged. The fix is
local to `MeltTask` and the integration tests are cheap.
Direct violation of Constitution Principle I.

**Independent Test**: An integration test that submits a melt
request with proofs summing to one unit less than
`invoiceAmount + exactFeeReserve` and asserts the mint returns
a typed `insufficient_input` error and never invokes
`gateway.pay`.

**Acceptance Scenarios**:

1. **Given** an invoice for 100 sats with a calculated fee
   reserve of 5 sats, **When** the client submits proofs
   summing to 104 sats, **Then** the request is rejected with
   `insufficient_input` and no external payment is initiated.
2. **Given** the same invoice, **When** the client submits
   proofs summing to exactly 105 sats, **Then** the request
   enters the burn-first state machine.
3. **Given** the same invoice, **When** the client submits
   proofs summing to 200 sats, **Then** the request enters
   the burn-first state machine. Excess is handled per
   existing change/fee policy (NUT-08 overpaid melt — out of
   scope for this spec, but the saga MUST record the
   overpayment for change return).

---

### User Story 2 — Proofs are burned in a durable transaction before external payment (Priority: P1)

When a melt request is well-formed and well-funded, the input
proofs MUST be marked spent/pending in the durable proof store
**before** any external payment call is issued. If the
external payment subsequently fails, the saga MUST transition
to an explicit compensation state rather than silently
re-spending the proofs.

**Why this priority**: This is the part of the finding that
creates the biggest reconciliation hole today. Pay-before-burn
means a JVM crash or vault outage between `gateway.pay` and
the proof-invalidation call can leave the system unable to
attribute external payments to specific proof debits. Direct
violation of Constitution Principle I ("durable financial
state").

**Independent Test**: A failure-injection integration test
that drives a melt with valid proofs, makes the external
payment provider succeed, then triggers a `RuntimeException`
on the invalidation step, and asserts the saga record is in
`PAYMENT_SENT_BURN_FAILED` and that the same melt is **not**
retryable until manual reconciliation.

**Acceptance Scenarios**:

1. **Given** a well-funded melt request, **When** the saga
   begins, **Then** the proofs transition from `UNSPENT` →
   `PENDING` in a durable transaction; only after that commit
   does `gateway.pay(quoteId)` run.
2. **Given** a saga in `PROOFS_HELD`, **When** `gateway.pay`
   succeeds, **Then** the saga transitions
   `PROOFS_HELD → PAYMENT_SENT → COMPLETED` (with proofs
   moving `PENDING → SPENT` in the final transition).
3. **Given** a saga in `PROOFS_HELD`, **When** `gateway.pay`
   fails with a definitive error (e.g.
   `PAYMENT_REJECTED`), **Then** the saga transitions
   `PROOFS_HELD → FAILED` and proofs return
   `PENDING → UNSPENT` in a durable transaction.
4. **Given** a saga in `PAYMENT_SENT` where the final
   `PENDING → SPENT` invalidation fails, **When** the failure
   is durable, **Then** the saga enters
   `PAYMENT_SENT_BURN_FAILED` and surfaces an operator alert;
   no automatic retry of the external payment is allowed.
5. **Given** a duplicate melt request for the same quote,
   **When** it arrives while the original is in any
   non-terminal state, **Then** it is rejected as
   `melt_in_progress`.

---

### User Story 3 — Saga state is observable and recoverable (Priority: P2)

The melt saga state MUST be queryable post-hoc so operators
can reconcile loss events without reading provider logs.

**Why this priority**: Useful but not blocking; depends on the
durable saga state from Story 2.

**Independent Test**: A read endpoint returns the saga's
current state and the timestamps of each transition for a
given `quote_id` or `melt_id`. Endpoint requires service-
account authentication per Constitution Security
Requirements.

**Acceptance Scenarios**:

1. **Given** any completed or failed melt, **When** queried
   by `quote_id`, **Then** the response includes the full
   state-transition timeline.
2. **Given** a saga in `PAYMENT_SENT_BURN_FAILED`, **When**
   the reconciliation tooling marks it resolved, **Then** the
   resolution is appended as a state transition (not
   overwritten).

---

### Edge Cases

- Two concurrent melt requests against the same proofs (the
  second must see the first's `PENDING` hold and be rejected;
  no double-burn).
- External payment returns ambiguous status (timeout, 5xx
  without `payment_hash`). The saga MUST treat that as
  `PAYMENT_UNKNOWN` and require operator/automated
  reconciliation; it MUST NOT auto-retry the payment.
- External payment succeeds but `gateway.pay` returns no
  `payment_hash` (provider quirk). Same as above —
  `PAYMENT_UNKNOWN`, no auto-retry.
- Proofs sum to exactly `invoiceAmount + exactFeeReserve`
  (boundary — must succeed).
- Proofs sum to `invoiceAmount + exactFeeReserve - 1`
  (boundary — must fail).
- Saga state machine survives process restart (no in-memory
  state for write decisions).
- NUT-08 overpaid melt: excess fee reserve must be returned
  to the customer via the change-output mechanism without
  altering the burn-first ordering.
- All amounts use `long`; no `int` /
  `Stream.mapToInt(...)` over amounts.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The mint MUST express the burn-amount check as
  `sum(proofs.amount) >= invoiceAmount + exactFeeReserve`,
  where each operand is a `long`. The existing total-plus-
  fees vs. total-plus-fees formulation MUST be removed.
  [NUT-05, Constitution I]
- **FR-002**: The mint MUST define `exactFeeReserve` from a
  single authoritative source per quote; the difference
  between `postMeltRequest.getFees` and
  `calculated_fee_reserve` (if any) MUST be documented and
  reconciled before this spec ships.
- **FR-003**: Before `gateway.pay(quoteId)` is invoked, the
  mint MUST mark all input proofs `PENDING` against the melt
  saga in a durable transaction. The transaction commit MUST
  precede the external payment call. [Constitution I]
- **FR-004**: The melt saga MUST persist explicit state
  transitions: `PENDING → PROOFS_HELD → PAYMENT_SENT →
  COMPLETED` for the happy path; `PROOFS_HELD → FAILED` on
  definitive payment failure; `PAYMENT_SENT →
  PAYMENT_SENT_BURN_FAILED` on burn failure after payment;
  `PROOFS_HELD → PAYMENT_UNKNOWN` on ambiguous payment
  provider response. Transitions MUST be append-only
  (Constitution-aligned audit trail).
- **FR-005**: A melt saga in any non-terminal state MUST
  reject concurrent requests for the same `quote_id` with
  `melt_in_progress`.
- **FR-006**: Proof state transitions MUST be atomic with the
  saga state transitions (single transaction):
  `UNSPENT → PENDING` on `PROOFS_HELD`;
  `PENDING → SPENT` on `COMPLETED`;
  `PENDING → UNSPENT` on `FAILED`.
  `PAYMENT_SENT_BURN_FAILED` MUST hold proofs in `PENDING`
  indefinitely until operator action. [Constitution I,
  cashu-vault Constitution I]
- **FR-007**: After definitive external payment failure, the
  mint MUST NOT auto-retry the external payment. Retry MUST
  require an operator-initiated reconciliation action or the
  saga returning to `PENDING` after operator triage.
- **FR-008**: For ambiguous external responses
  (`PAYMENT_UNKNOWN`), the mint MUST NOT advance to
  `COMPLETED` without independent confirmation (a webhook or
  a follow-up provider call confirming the exact
  `payment_hash` and amount); proofs MUST remain `PENDING`
  until then. [Constitution VI — strict parsing]
- **FR-009**: All amount arithmetic in `MeltTask` MUST use
  `long`. `int` / `Stream.mapToInt(...)` over amounts MUST
  be removed. [Constitution I]
- **FR-010**: The saga state and full transition timeline
  MUST be queryable by `quote_id`. Read endpoint requires
  service-account authentication.
- **FR-011**: Operator-visible alerts MUST fire on entry into
  `PAYMENT_SENT_BURN_FAILED` and on every transition into
  `PAYMENT_UNKNOWN`. [Constitution I — operator-visible
  alerts]
- **FR-012**: Each saga state transition MUST be logged with
  structured fields (`quote_id`, `melt_saga_id`,
  `from_state`, `to_state`, `proof_count`, `input_amount`,
  `invoice_amount`, `fee_reserve`, `provider`,
  `provider_event_id` if any).
- **FR-013**: NUT-08 overpaid-melt change return MUST be
  computed against the persisted saga record, not against
  the in-memory request, and MUST be issued only after
  `COMPLETED`. [NUT-08]
- **FR-014**: Javadoc on `MeltTask` MUST link to the NUT-05
  spec section the code implements, pinned to a specific
  commit hash on
  [github.com/cashubtc/nuts](https://github.com/cashubtc/nuts/blob/main/05.md).
  [Constitution II]

### Key Entities

- **MeltSaga**: durable record keyed by `melt_saga_id` with
  `quote_id`, `invoice_amount` (long), `exact_fee_reserve`
  (long), `current_state`, `proof_count`, `input_amount`
  (long), `payment_hash` (nullable), `provider_event_id`
  (nullable), timestamps. Hibernate Envers audit-tracked.
- **MeltSagaTransition**: append-only timeline keyed by
  `(melt_saga_id, seq)` with `from_state`, `to_state`,
  `reason`, `at` (database `now()`).
- **Proof (cashu-vault)**: existing; gains a `melt_saga_id`
  foreign key while `PENDING` so the burn-first transition
  is exclusive.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of under-funded melt requests (proofs sum
  < invoice + exactFeeReserve) are rejected with
  `insufficient_input` and produce zero external payment
  calls. (Integration tests across boundary cases.)
- **SC-002**: 0 production sagas reach `COMPLETED` without
  `proofs` in `SPENT`. (Periodic reconciliation.)
- **SC-003**: 0 production sagas have `gateway.pay` invoked
  before `PROOFS_HELD` commit. (Logs assertion:
  `PROOFS_HELD` precedes `PAYMENT_SENT` for every
  `melt_saga_id`.)
- **SC-004**: 100% of `PAYMENT_UNKNOWN` sagas have a
  downstream resolution (operator action or independent
  confirmation) within the operator SLA; none silently
  auto-settle.
- **SC-005**: All melt amount fields use `long` in code;
  static-analysis check passes.
- **SC-006**: NUT-05 advertised support via NUT-06 matches
  the integration-test set 1:1.

## Assumptions

- The mint and the underlying vault share a transactional
  boundary (or compensating-action equivalent) sufficient to
  make `UNSPENT → PENDING` atomic with saga creation. If
  not, this spec depends on a vault-side change tracked in
  the cashu-vault spec backlog.
- `Gateway.pay` is the single integration point for outbound
  payment; it returns either a definitive success/failure
  or an ambiguous outcome.
- The change/fee policy (handling proof sums >
  `invoiceAmount + exactFeeReserve`) is governed by NUT-08
  semantics; this spec does not change the policy, only the
  ordering relative to burn.
- Operator tooling already exists or can be extended to
  drive the manual transitions out of
  `PAYMENT_SENT_BURN_FAILED` / `PAYMENT_UNKNOWN`. Tooling
  design is out of scope.
- Migration of in-flight melts at deploy time will be
  handled in planning; this spec assumes a maintenance
  window or a draining strategy is acceptable.
- Hibernate Envers is the natural audit mechanism for
  `MeltSaga.current_state` transitions.
