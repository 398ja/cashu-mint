# A fee change is a keyset rotation, not an edit

An Operator sets a keyset's NUT-02 `input_fee_ppk` when the keyset is
provisioned. We decided that changing the fee afterwards is not an edit to that
keyset: it archives the keyset and provisions a new one, through the same
rotation the admin already has.

## Why a live edit is the wrong shape

Under keyset id v1 the fee is not part of the id, so editing it in place would
work mechanically. That is precisely the problem. Wallets cache a keyset by id
and treat it as immutable, so a mint that changed the fee under a stable id
would price transactions by a number the wallet does not have. The wallet
computes `sum(inputs) - fees == sum(outputs)` from its cached fee, the mint
computes it from the new one, and every transaction the wallet constructs is
refused as unbalanced until it happens to re-read `/v1/keysets`. Nothing tells
it to. The failure is silent, affects every holder of that keyset at once, and
looks to the wallet like a mint that has started rejecting valid transactions.

[NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md) settles the question
for us in the other direction too. Keyset id v2 puts `input_fee_ppk` in the
preimage the id is derived from, so under v2 a keyset with a different fee *is*
a different keyset — the id changes whether or not we intend it to. Choosing
rotation now means adopting v2 later changes the id derivation and nothing else.
Choosing in-place edits would mean building an operation that v2 makes
impossible, and then withdrawing it.

So the rule is the same under both versions, and only the reason differs: under
v2 a fee change is a new keyset by definition, and under v1 it is a new keyset
by our decision, because that is what wallets already assume.

## What this means in practice

Rotation is already the operation that retires one keyset and stands up its
replacement, and it already does the two things a fee change needs. The old
keyset is archived rather than deleted, so under
[ADR-0004](./0004-archived-keysets-must-refuse-to-sign.md) it stops signing but
goes on verifying and redeeming indefinitely — proofs issued at the old fee stay
spendable at the old fee, which is the honest outcome for a holder who accepted
a token under the terms of the day. The new keyset gets a new id, so wallets
discover the new fee through the ordinary path: `/v1/keysets` lists it as active
with its `input_fee_ppk`, and outputs must be built from an active keyset.

Both fees are therefore correct simultaneously, and each proof is priced by the
keyset that issued it. The mint already does this: `MintKeySetResolver` resolves
every input against its own keyset, so a swap mixing old and new proofs charges
each at the fee it was issued under. A fee change needs no migration and no
window in which holders are mispriced.

This also answers whether the mint must be suspended for a fee change. It must
not. Rotation leaves the unit without an active keyset only for the moment
between archiving and provisioning, and redemption is unaffected throughout, so
a fee change costs no more availability than any other rotation.

## Consequences

The fee is set at provisioning and is immutable for the life of the keyset. This
is a real constraint, not an incidental one: there is deliberately no endpoint
that writes a fee onto an existing keyset, because such an endpoint would be
unimplementable under v2 and unsafe under v1.

The cost is that a fee change is heavier than editing a number. It generates a
new keyset, new key material for every denomination, and a keyset the mint must
keep serving for as long as its proofs circulate. A mint that changed fees often
would accumulate keysets. We think that cost is the point: it prices fee changes
at what they actually cost the wallets holding the mint's tokens, and a mint
changing its fee daily is a mint whose users cannot rely on it.

Because the fee is fixed at provisioning, it defaults to zero and stays there
unless an Operator names one. A mint that never configures a fee behaves exactly
as it did before fees existed.

Fee changes are recorded in the Audit Trail against the Operator, like every
other rotation, so the question "who changed the fee, when, and to what" is
answerable after the fact.
