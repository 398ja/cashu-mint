# Phase 0 Research: Melt Path Burn-First Ordering and Burn-Amount Check

**Feature**: 002-melt-burn-ordering
**Date**: 2026-05-22
**Status**: Resolved

---

## R1. `getFees` vs. `calculated_fee_reserve` — what is `exactFeeReserve`?

**Question**: FR-002 mandates that `exactFeeReserve` be defined
from a single authoritative source per quote. Today, `MeltTask`
mixes `postMeltRequest.getFees()` and `calculated_fee_reserve`
in the burn check; the relationship between them is non-obvious.

**Options**:

1. `exactFeeReserve = postMeltRequest.getFees()` — caller asserts
   the fee reserve.
2. `exactFeeReserve = calculated_fee_reserve` — mint computes it
   from the invoice (e.g. via `Gateway.estimateFee(invoice)`).
3. Cross-check: caller-asserted == mint-computed; reject on
   mismatch.

**Decision**: Option 2 (mint computes), with Option 3 as a
defence-in-depth assertion in `staging`/`prod` profiles.

**Rationale**: Constitution Principle II (Provider Authority,
adapted): the mint's computation is closer to the actual
provider's fee semantics; relying on the caller risks
under-collection. The cross-check catches client/mint drift early
without making it a hard fail. The persisted `MeltSaga` row
records both values for forensics.

---

## R2. Saga state machine — what about a missing
`PROOFS_HELD → PAYMENT_SENT_BURN_FAILED` analog when burn fails
*before* payment is sent?

**Question**: The spec defines compensation states for failures
*after* `gateway.pay`. What about a failure during
`UNSPENT → PENDING` (the `PROOFS_HELD` transition itself)?

**Options**:

1. Implicit: if the transaction fails, no saga row is committed,
   and there's nothing to clean up. The client gets a 5xx.
2. Pre-commit a saga in `INITIATED` state, then advance to
   `PROOFS_HELD`. If the `UNSPENT → PENDING` step fails, the
   saga is in `INITIATED` with no proofs held; mark it
   `FAILED`.

**Decision**: Option 1.

**Rationale**: Simpler. The `PROOFS_HELD` transition is one
PostgreSQL transaction: it commits both the proof state change AND
the saga row insertion. If the transaction rolls back, no state
mutation happens — there's nothing to compensate. The client
retries with the same proofs and the same outcome. No
`INITIATED` state in the enum simplifies the state machine.

---

## R3. The `melt_saga_id` foreign key on `cashu-vault`'s `proof_entity`

**Question**: FR-006 makes the `PENDING` hold exclusive to one
saga. The cleanest way to express this is a `melt_saga_id` column
on `proof_entity` with a partial unique index. But that means
modifying cashu-vault, which is a separate repository.

**Options**:

1. Add the column + index to `proof_entity` in cashu-vault.
2. Maintain an exclusivity index in `cashu-mint-jpa`'s own table
   (e.g. `melt_saga_proof_lock`).
3. Use a distributed lock (Redis, ZooKeeper).

**Decision**: Option 1.

**Rationale**: The proof's exclusive-hold property is intrinsic
to proof state — it belongs in the proof's own row. Option 2
adds a denormalisation that has to be kept in sync. Option 3
introduces operational complexity. Cross-repo coordination cost
of Option 1 is low: one nullable column + one partial index +
one Flyway migration. The cashu-vault spec backlog tracks this
change; it lands in lock-step with this feature.

Partial unique index:

```sql
ALTER TABLE proof_entity
  ADD COLUMN melt_saga_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_proof_pending_saga
  ON proof_entity (id)
  WHERE melt_saga_id IS NOT NULL;
```

A proof can be claimed by at most one saga at a time; freeing
sets `melt_saga_id = NULL`. The mint repository methods that move
proofs `PENDING → SPENT` / `PENDING → UNSPENT` clear this
column.

---

## R4. `Gateway.pay(quoteId)` — surfacing ambiguous outcomes

**Question**: The current `Gateway.pay(...)` signature returns a
boolean (or throws). The spec needs three outcomes: definitive
success, definitive failure, and ambiguous (`PAYMENT_UNKNOWN`).

**Options**:

1. Cross-repo change: redefine `Gateway.pay(...)` to return a
   `PaymentOutcome` sealed type with `Success`,
   `DefinitiveFailure`, and `Unknown` variants.
2. Keep the existing signature; introduce a separate query
   (`Gateway.checkPaymentStatus(quoteId)` already exists) and
   classify locally by combining the two.
3. Local wrapper port (`LightningPaymentPort`) that calls
   `Gateway.pay(...)` and `Gateway.checkPaymentStatus(...)`
   under a configurable timeout and emits `PaymentOutcome`.

**Decision**: Option 3 (local wrapper) for v1, with Option 1 as
the long-term cross-repo migration.

**Rationale**: Option 1 requires payment-adapter to ship a new
return type to multiple consumers — slow. Option 3 lands
immediately, isolates the parsing logic to one class, and lets
us evolve the cross-repo API without blocking this feature. Once
payment-adapter exposes a typed outcome (tracked in
payment-adapter Principle II — "Provider Authority"), the
wrapper becomes a thin pass-through.

`LightningPaymentPort` contract:

```java
sealed interface PaymentOutcome
    permits PaymentOutcome.Success,
            PaymentOutcome.DefinitiveFailure,
            PaymentOutcome.Unknown {

  record Success(String paymentHash, long amountSettled,
                 long feePaid, String providerEventId) implements PaymentOutcome {}
  record DefinitiveFailure(String reason, String providerCode) implements PaymentOutcome {}
  record Unknown(String reason) implements PaymentOutcome {}
}

interface LightningPaymentPort {
  PaymentOutcome pay(String quoteId, Duration timeout);
}
```

The implementation:
1. Calls `Gateway.pay(quoteId)` with the explicit timeout.
2. On clean success with all required fields → `Success`.
3. On HTTP-level definitive failure (4xx or specific provider
   error codes) → `DefinitiveFailure`.
4. On timeout, 5xx, missing `payment_hash`, missing status, or
   any other ambiguity → `Unknown`.

---

## R5. `PAYMENT_UNKNOWN` — what triggers transition to terminal?

**Question**: A saga in `PAYMENT_UNKNOWN` cannot auto-advance.
What mechanism moves it to terminal (`COMPLETED` or `FAILED`)?

**Options**:

1. Scheduled job polls `Gateway.checkPaymentStatus(quoteId)` for
   every `PAYMENT_UNKNOWN` saga every N minutes until definitive
   answer.
2. Operator-only manual resolution via the admin endpoint.
3. Both — automatic polling for a bounded window, then escalate
   to operator alert.

**Decision**: Option 3.

**Rationale**: Automatic polling resolves the common case (a
transient timeout that the provider eventually confirms one way
or the other). Bounded window (e.g. 1 hour, configurable) avoids
indefinite polling on a truly stuck saga. After the window,
operator alert + manual resolution. FR-007 ("no auto-retry") is
preserved because polling `checkPaymentStatus` is *not* a retry
of `pay`; it's an idempotent read of the provider's state.

The polling job lives in `cashu-mint-protocol` as a scheduled
component (using Spring's `@Scheduled` + virtual-thread
executor). FR-011 emits an alert at saga entry into
`PAYMENT_UNKNOWN` AND on every poll-window timeout.

---

## R6. NUT-08 overpaid-melt change return — where is the change minted?

**Question**: When `sum(proofs) > invoiceAmount + exactFeeReserve`,
NUT-08 returns the difference as new blinded signatures. Where in
the saga timeline does this happen?

**Options**:

1. Compute change synchronously inside `MeltTask` after
   `COMPLETED`.
2. Persist the change requirement in the saga; let a separate
   change-return task issue the signatures asynchronously.

**Decision**: Option 1 for v1.

**Rationale**: Simpler. The change is computed from the persisted
saga record (FR-013), so it's deterministic. The client receives
the change in the same response. Edge case: change computation
itself fails — saga stays `COMPLETED`, change request is
persisted as a follow-up obligation on the saga; operator alert.
This is rare enough that the synchronous path is acceptable.

---

## R7. Idempotent retry of the *whole melt* — what does the client see?

**Question**: A client may retry the same melt request (same
`quote_id`, same proofs). How does the response shape differ
across saga states?

**Options**:

1. Always re-attempt: if `PENDING`, return `melt_in_progress`; if
   `COMPLETED`, return success.
2. Return the saga's terminal response (success or failure) for
   any retry of a non-terminal request, plus a clear
   "in progress" indicator for non-terminal states.

**Decision**: Option 2.

**Rationale**: FR-005 already requires `melt_in_progress` for
non-terminal duplicate requests. For terminal sagas, returning
the original response is the NUT-19 cached-responses idiom.
Implementation: a `melt_response_cache` column on `melt_saga`
that stores the serialised response (similar to spec 001's
`signatures_json` in `IssuanceRecord`).

---

## R8. Where does the operator alert go?

**Question**: FR-011 fires operator-visible alerts on
`PAYMENT_SENT_BURN_FAILED` and `PAYMENT_UNKNOWN`. What's the
delivery mechanism?

**Options**:

1. Structured log line with a known prefix (operator dashboards
   tail logs).
2. Micrometer counter + Grafana alert.
3. Dedicated Slack / PagerDuty integration.

**Decision**: Option 1 + Option 2.

**Rationale**: Log line gets it into SIEM and dashboards
immediately. Micrometer counter (e.g.
`cashu_mint_melt_saga_payment_unknown_total`) lets operations
team write Prometheus alert rules. Dedicated paging is out of
scope; the operator dashboard is the entry point.

---

## R9. Saga TTL — when do non-terminal sagas expire?

**Question**: A saga in `PROOFS_HELD` or `PAYMENT_UNKNOWN` could
in principle stay there forever. Should there be a TTL?

**Options**:

1. No TTL — sagas live until terminal.
2. TTL on `PROOFS_HELD` (e.g. 5 minutes) — if `gateway.pay`
   hasn't been called by then, refund proofs.
3. TTL on `PAYMENT_UNKNOWN` (e.g. 1 hour matching the
   reconciliation window from R5).

**Decision**: Option 2 + Option 3, both configurable per
profile.

**Rationale**: `PROOFS_HELD` TTL covers the JVM-crash case
between commit and `gateway.pay` invocation: a scheduled sweep
moves stale `PROOFS_HELD` sagas to `FAILED` and refunds proofs.
`PAYMENT_UNKNOWN` TTL is the polling window from R5. No TTL on
`PAYMENT_SENT_BURN_FAILED` (operator-only resolution per
FR-007).

---

## Open items deferred to Phase 2 (/speckit.tasks)

- Cross-repo coordination protocol for the cashu-vault
  `melt_saga_id` column migration (R3) — needs a dated PR plan.
- Cross-repo coordination for the eventual payment-adapter
  `PaymentOutcome` return type (R4 long-term migration).
- Exact Micrometer metric names (R8).
- Profile-specific TTL defaults for `PROOFS_HELD` and
  `PAYMENT_UNKNOWN` (R9).

No NEEDS CLARIFICATION items remain.
