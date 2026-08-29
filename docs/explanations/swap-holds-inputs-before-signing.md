# Why a swap holds its inputs before it signs

This explains why a swap takes an exclusive claim on its input proofs *before*
it signs any output, and why that ordering is the only arrangement in which a
mid-swap failure cannot let the same value be redeemed twice.

It is the companion to [Why validation runs before
signing](validation-before-signing.md). That one covers failures *before*
signing. This one covers everything after.

## The problem: two writes that cannot share a transaction

A swap has to do two durable things: publish blind signatures for the outputs,
and spend the inputs. The signature vault and the proof vault are each reached
over their own REST API, so there is no transaction that can span both. One of
them happens first, and the mint can crash, time out, or get a 404 in between.

The original ordering signed first:

```
sign every output   → durable in the signature vault
spend every input   → may fail
```

If the spend failed, the swap ended with the outputs signed and retrievable
through NUT-09 restore, and the inputs still `UNSPENT`. The client saw a 500,
and reasonably either retried or spent those inputs somewhere else. The same
value was now redeemable twice: once as the new outputs, once as the old
inputs. The mint had no record that anything was wrong.

The spend was also a loop, one proof at a time, so a failure part-way down the
list left the inputs *partly* spent. That is the same hazard at finer grain.

## Why "spend first" is not the answer

Reversing the order closes the double-spend, and it is what the melt path does,
but melt has a reason a swap does not. Melt's irreversible step is an outbound
Lightning payment: once the money has left, the inputs must be gone, and burning
them first is the conservative choice.

A swap has no outbound payment. Its only irreversible step is one the mint
controls. If a swap burned its inputs and then failed to sign, the wallet would
be left with nothing at all: money destroyed, no outputs, no recourse. That
failure is worse than the one being fixed. A mint should never be able to
destroy a wallet's funds because a vault call timed out.

## The rule: hold, sign, commit

The swap does what the melt saga does, but with a *hold* rather than a burn:

1. **Claim.** `SwapProofHold.claim` moves every input to `PENDING`, bound to
   this swap, in a single atomic insert-or-claim per proof. Either all inputs
   bind or the swap is refused with `proofs_not_bound`.
2. **Sign.** The outputs are signed and stored.
3. **Commit.** `SwapProofHold.commit` flips the whole hold to `SPENT` in one
   set-based call.

The hold, not the `SPENT` state, is what closes the hazard. The vault's claim is
gated on `state = 'UNSPENT' AND melt_saga_id IS NULL`, so a proof held by an
unresolved swap cannot be claimed by any later swap or melt even though it is
not yet spent. Held means unspendable.

That gives each failure point a safe outcome:

| Failure point | Inputs | Outputs | Why it is safe |
| --- | --- | --- | --- |
| Before the claim | `UNSPENT` | none | Nothing happened. |
| The claim itself | released to `UNSPENT` | none | Partial claim is compensated; wallet keeps its money. |
| During signing | released to `UNSPENT` | none signed | Nothing durable was published, so returning the inputs is safe and kind. |
| During the commit | stay `PENDING`, still held | signed | The inputs are unspendable, so the value cannot be doubled. |

The last row is the pathological case, and it is deliberately *not* resolved
automatically. Releasing the hold there would recreate the exact double-spend
this design exists to prevent, because the outputs are already redeemable. So
the hold is left standing and the client is told `proofs_pending` — which is
the truth about their inputs, and tells a wallet to wait rather than to spend
those inputs elsewhere.

## Why the partial failure disappeared

The mid-list hazard is gone structurally rather than by careful handling. The
claim is one call for the whole input list and returns how many proofs actually
bound; the swap compares that against the input count and refuses unless every
one is held. The commit is likewise one call keyed on the hold, not a loop. A
swap therefore cannot end with some inputs spent and others not.

A commit that reports fewer proofs spent than were held is treated as a
failure, not a success. Otherwise a vault whose commit did nothing at all would
return a perfectly ordinary-looking swap whose outputs were signed and whose
inputs were untouched.

## What an operator has to do

A stranded hold is visible in the logs under `[swap-hold][alert]
SWAP_SIGNED_COMMIT_FAILED`, and its inputs are the rows carrying that hold id.
There is one rule for resolving it:

> A hold stranded after signing must be **committed**, never released.

The outputs are already in the wild. Committing the hold completes a swap the
wallet has, in effect, already received. Releasing it hands the wallet its
inputs back on top of outputs it can still redeem.

## How a stranded hold is resolved

`SwapHoldReconciler` applies that rule on a schedule, so a stranded hold no
longer waits for someone to read the alert log.

It can only apply it because the hold records which side of the signing step it
reached. A held proof looks identical either way, so without that record the
sweep would be guessing, and the wrong guess is the double-spend the hold
exists to prevent. The phase is written *before* the first signature rather
than after: a crash on either side of that write must read as "signing may have
begun".

| Phase | Meaning | Resolution |
| --- | --- | --- |
| `HELD` | claimed, nothing signed | release: the wallet keeps its money |
| `SIGNING` | an output may exist | commit: never release |

Committing a `SIGNING` hold is right even when no signature was actually
produced. That reading costs one wallet its inputs, which an operator can make
good from the hold record; the opposite mistake inflates the mint's supply and
cannot be undone.

Note this is the **opposite direction** to `MeltSagaReconciler`, which releases
the proofs of a stale melt. The asymmetry follows from which side of its
irreversible step each flow is stranded on: a melt holds proofs *before* paying
and can still decide not to pay, while a swap in `SIGNING` is already past the
point where it could take its outputs back.

Holds younger than `cashu.mint.swap.hold-ttl` are left alone, so a slow but
healthy swap is never interrupted.

## Related

- [Why validation runs before signing](validation-before-signing.md) — the
  guarantee for failures *before* the signing loop.
- [Spending a proof exactly once across the secret encoding
  change](spent-proof-key-encoding.md) — why the hold keys each row on the
  point the proof was actually issued under.
- [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md) — the swap
  operation.
