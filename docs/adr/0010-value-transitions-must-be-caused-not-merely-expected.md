# ADR 0010: A value transition must be caused, not merely expected

- Status: Accepted
- Date: 2026-09-20
- Issues: [#459](https://github.com/398ja/cashu-mint/issues/459), [#460](https://github.com/398ja/cashu-mint/issues/460), [#461](https://github.com/398ja/cashu-mint/issues/461)
- Extends: [ADR 0002](0002-db-derived-gauges-for-operational-invariants.md)

## Context

A 1000 EUR sale on staging delivered 200 EUR to the customer.

Nothing failed. Every invoice settled, every webhook was received, classified and persisted
within ~600ms, and no queue backed up. The mint did everything it was asked to do. What it was
never asked to do was create the funding row that turns an accepted payment into a redeemable
voucher, because that step was reached only from an inbound client mint request.

Payment acceptance and funding creation were therefore not causally linked. They were
*coincidentally* linked, through a client that happened to still be polling:

```
Invoice PAID ──▶ webhook_event(accepted)  ✅ durable
                        │
                        ╳  no link
                        │
Client polls ──────────▶ voucher_funding ──▶ ISSUED
                  (only trigger)
```

Sequential sales hid this for months by giving each part its own polling window. A five-part
auto-split fired five payments into one window and exhausted the client's budget
(30 × 2s = 60s) after two. Sixty-eight `voucher_quote` rows were stranded `UNFUNDED`, every one
with a PAID invoice, totalling 3432 EUR.

The audit in #460 looked for the same shape everywhere and found the rule held without
exception, in both directions:

> Every state machine with a scheduled reconciler is at zero stranded rows. Every one without is
> stranded.

`melt_saga`, `swap_hold`, `voucher_purchase`, `voucher_send` and `receive_escrow` all have
reconcilers and all sit at exactly zero. `voucher_quote` (68 rows) and `mint_quote` (2 rows, three
weeks old) do not, and are stranded. A second instance in `imani-gateway-portal` — lapsed
cashbacks expired only on the read path, 17 stranded, 53.19 EUR of merchant float locked — was
found by the same rule and confirms it generalises beyond this repository.

## Decision

**A state transition that moves value must be caused by the event that obliges it, and must
additionally be swept on a schedule. Neither half is sufficient alone.**

Concretely, for every persisted state machine carrying value:

1. **Close the window.** The transition commits in the same transaction as the event that
   justifies it. For #459 this means the funding row is created by the webhook that accepted the
   payment, not by whatever happens to arrive next.

2. **Sweep the state.** A `@Scheduled` reconciler finds rows stranded in a non-terminal state past
   a grace period and drives them through *the same code path* the request flow uses. Two copies
   of a money-moving decision will diverge, and the failure mode of divergence is measured in
   customer funds.

3. **Export the standing liability as a gauge**, per ADR 0002, re-derived from SQL rather than
   counted at the transition. These are durations in database state: a counter can neither
   express "stuck for three weeks" nor survive a restart with its standing count intact.

The second half is what makes the guarantee real. The first is one code path that must be
correct; the second catches everything that bypasses or breaks it — a bug, a rollback, a manual
edit, or a future payment provider wired straight to the event table.

### The sweep direction is a decision, not a default

Which way a reconciler resolves a stranded row follows from **which side of its irreversible step
the flow is stranded on**, and the answer differs per machine:

| Machine | Stranded before or after the irreversible step | Sweep direction |
|---|---|---|
| `melt_saga` `PROOFS_HELD` | before paying | fail, release the proofs |
| `swap_hold` `SIGNING` | after signing may have begun | commit; releasing would double-spend |
| `voucher_quote` `UNFUNDED` | after the customer's money was taken | fund; abandoning loses value |
| `mint_quote` `PAID` | after payment, but issuing needs client outputs | **none possible** — leave claimable, alert |

Copying a neighbouring reconciler's direction without asking this question is how a sweep turns
into the defect it was meant to prevent.

### Sometimes the answer is that no sweep is correct

`mint_quote PAID` is the case that proves the rule has a boundary, and #460 originally proposed
the wrong answer for it: *expire the quote and flag the payment for refund*. Both halves fail.

Issuance CAS-transitions **from** `PAID`, so moving the row to `EXPIRED` permanently bars the
customer from money the mint has already taken. `MintTask.alreadyPaid` had already settled this
question in the opposite direction, and its reasoning is worth repeating because it is the
general principle:

> An expiry bounds how long the payer has to pay an invoice, not how long the mint will honour a
> payment it has already taken. Rejecting one takes the customer's money and issues nothing.

And "flag for refund" has no implementation to flag: the mint holds no Lightning refund
machinery, so the phrase describes an operator process, not a code path. A sweep that expires
rows would therefore convert a *visible* stranded payment into an *invisible* destroyed claim,
while making the gauge read zero — the tidy count being precisely the danger.

So the rule's second clause: **a reconciler is mandatory only where a correct resolution exists.**
Where none does, the requirement becomes a gauge and an alert that stay loud, and the state must
remain in whatever form keeps the customer's claim alive. Tidying a money-at-risk state into a
terminal one to satisfy a coverage rule is worse than the stranding it was meant to fix.

### What a sweep cannot do

`mint_quote PAID → ISSUED` and `voucher_quote FUNDED → ISSUED` both require the client's blinded
outputs, which the mint never persists and does not hold until it is asked. No reconciler can
issue on the client's behalf.

This bounds the guarantee honestly: the sweep restores **funded and waiting**, not **issued**. The
value becomes durably backed so a returning client — or the gateway's own retry — can complete it,
where before there was nothing to return to. Where half A is impossible for the same reason (the
mint quote path), the sweep is the *only* available fix, which raises its priority rather than
lowering it.

### The rule does not cross service boundaries by itself

Every machine above is swept by asking the database a question about rows it already holds. That
works precisely because the obligation and the evidence live in the same schema.

It fails at a service boundary. Validating the #459 fix against staging turned up 7 quotes that
are `PAID` in the payment adapter with **no `webhook_event` in the mint at all** — one of them a
`mint_quote` still reading `UNPAID` while the adapter holds the customer's money (#462). No
mint-side reconciler can find those, because the mint's entire notion of "this was paid" *is* the
webhook event: a delivery that never happened leaves nothing to reconcile against.

So the rule needs a second clause. **When the obligation and the evidence live in different
services, the gauge belongs to the side that knows the event happened**, and the reconciler must
compare the two sides rather than query one. A mint-side invariant cannot express "money was taken
elsewhere and I was not told", and a green gauge that cannot see the failure is worse than no
gauge, because it reads as proof of health.

This also bounds what an ArchUnit rule of the #461 shape can do: it sees one repository, and a
state machine can be stranded by a peer service that never calls it.

## Consequences

**A new state costs a sentence of justification.** #461 encodes this as an ArchUnit rule with an
exemption list where each entry carries a *reason*, not just a state name. That friction is the
intent: the alternative cost is measured in customer money and three-week-old stranded rows nobody
noticed.

**The obvious test would not have worked.** "Every non-terminal state must be named by a
reconciler" passes green while #459 is live, because `UNFUNDED` is referenced in the JPQL of the
method the *client* calls. Any encoding must distinguish *handled on the request path* from *swept
on a schedule*; the discriminating signal is a time-bounded sweep query
(`findUnresolvedOlderThan`, `findPaidButUnfunded`), because no client flow needs "find everything
older than X" — a client already knows which row it is asking about.

**The reconciler working is not the same as the system being healthy.** A steady recovery rate
means the causal link is leaking, so the sweep's own activity is a counter
(`cashu_mint_voucher_funding_reconciled_total{outcome}`) and is alerted on separately from the
liability gauge.

**Exemptions decay.** ArchUnit rules of this shape accumulate silencers until they are decorative.
Pairing the rule with the DB-derived gauge is the guard: a wrongly-exempted state still surfaces
as a non-zero gauge, because the database does not care what the exemption list claims.

## Alternatives considered

**Make the client more persistent.** A longer polling budget would have delivered this particular
sale and left the defect intact for the next one. The client behaviour was in fact correct
throughout — `imani-wallet` reported a truthful "200 of 1000 delivered" error — and no client-side
timeout can be long enough for a client that has crashed.

**Only close the window (A alone).** Solves today's incident and leaves nothing to catch the next
code path that writes an accepted event. It also cannot heal the 68 rows already stranded.

**Only sweep (B alone).** Every voucher would then be funded seconds late by a background job,
making the normal path depend on a timer. Worse, the leak would be invisible, because recovery
*is* the mechanism rather than the exception.

**Fix it per-consumer, in the gateway.** Tempting because the gateway already retries. It solves
the symptom for one caller and leaves the trap armed for every other, which is the same reasoning
that put the funding attach on the client path in the first place.
