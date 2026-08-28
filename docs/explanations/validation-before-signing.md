# Why validation runs before signing

This explains why the mint validates a transaction completely before it signs
anything, and why that ordering is a security property rather than a matter of
taste.

## The problem: a signature is not undone by an exception

`SwapTask` used to sign every blinded message first and check the NUT-02 balance
equation afterwards. When the check failed, the request returned an error and
the caller saw a rejection. The signatures, however, had already been produced
and stored in the signature vault by `SignBlindedMessageTask`.

Nothing rolled them back. `SwapTask` calls no `@Transactional` method, so there
was no transaction to abort; the vault write had simply happened. And the vault
is not a private cache. It is the store NUT-09 restore reads: a wallet that
presents the same blinded message to `POST /v1/restore` is handed the signature
back. A swap the mint refused therefore still yielded usable signatures to
anyone who knew the blinded messages, which the wallet making the rejected
request does by construction.

The fix is not a rollback. It is an ordering: decide the whole transaction
first, then sign.

## The rule

Every check that can be decided from the request plus static keyset
configuration runs before the signing loop. Concretely:

1. `ValidateTransactionTask` — duplicate inputs, duplicate outputs, mixed units,
   inactive output keysets, previously signed outputs.
2. `VerifyProofsTask` — the spending conditions on each input.
3. `VerifyFeesTask` — the single NUT-02 balance equation,
   `sum(inputs) - fees == sum(outputs)`.
4. Only then, `SignBlindedMessageTask` for each output.

Steps 1 and 3 read nothing but the request and the keysets, so there is no cost
to running them early and no information they could only learn from a signature.
That is what makes the ordering free.

## Why the balance equation moved

`VerifyFeesTask` used to sum the *response*: it added up the blind signatures
that had just been produced. That is what forced it to run after signing. But
the sum of the blind signature amounts is by construction the sum of the
requested blinded message amounts, because each signature is issued for the
amount its blinded message asked for. Reading the request instead loses no
information and removes the dependency on the response, so the check can run
whenever we like.

At the same time `VerifyProofsTask.validateAmounts` enforced a second, contradictory
equation, `sum(inputs) == sum(outputs)`, with no fee term. NUT-02 states exactly
one equation and it includes fees, so the two could agree only while every
keyset charged nothing. `validateAmounts` now checks only that amounts are
positive, and the balance is decided in one place.

## What a test can observe

The ordering is only credible if something fails when it regresses.
`SwapTaskRejectionLeavesNoSignatureTest` submits an unbalanced swap and then
asserts three things: the error is the spec's unbalanced-transaction condition,
no `SignBlindedMessageTask` was ever constructed, and the signature vault
returns nothing for the output that was refused. The last assertion is the one
that speaks to the vulnerability, because it interrogates the same store NUT-09
restore would read.

## Related

- [Error codes](../reference/error-codes.md) — the codes these validations raise.
- [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md) — the balance equation.
- [NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md) — signature restore.
