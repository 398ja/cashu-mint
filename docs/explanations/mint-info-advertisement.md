# Why `/v1/info` is derived from the wiring

`GET /v1/info` is not a description of the mint. It is a promise to wallets. A
wallet reads the `nuts` map to decide what it may attempt: whether to lock a
proof to a public key, whether to expect a DLEQ proof, whether it is safe to
replay a melt after the connection dropped. Getting it wrong is not cosmetic. It
makes wallets take code paths the mint cannot honour, and the failure surfaces at
the worst possible moment, mid-transaction, with funds in flight.

This document explains why the advertisement is generated from code rather than
written down, and what that costs.

## How it drifted

The mint used to serve `/v1/info` from `mint.yaml`, a file packaged inside the
jar. It said the mint was called "Bob's Cashu mint", that it ran
`Nutshell/0.15.0`, that it lived at `https://mint.host` and at a `.onion`
address copied from the spec examples, and it published a `pubkey` that was
identical for every deployment of this codebase because it was a literal in a
checked-in file.

The `nuts` map was wrong in both directions at once. NUT-19 was implemented (the
melt saga has persisted a `melt_response_cache` since spec 002) and never
advertised, so wallets that could have safely replayed an interrupted melt did
not know it. Meanwhile entries stayed `true` while the code beneath them changed.

There was a guard test. It parsed `mint.yaml` and compared the advertised NUT
numbers against a hand-maintained `EXPECTED` constant. That is the shape of the
problem, not the solution: two hand-maintained lists agreeing with each other
says nothing about whether either agrees with the code. Both could be, and were,
wrong together. The version string is the clearest case. No test that compares
lists could ever have caught `Nutshell/0.15.0`, because the lie was not an
inconsistency between two documents. It was a document that nothing checked
against reality.

## The rule

The single idea behind the current design is that **it should not be possible to
state a capability separately from implementing it**.

Concretely:

- **Which NUTs are advertised is not configuration.** It is derived from
  [`NutSupport`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NutSupport.java),
  an enum where each constant names the type, and where useful the member, whose
  existence is what makes the claim true. `DefaultMintInfoService` builds the map
  by walking that enum. There is no file to edit and therefore no file to forget.
- **The version is not configuration either.** It comes from
  `mint-build.properties`, which Maven fills in with the artifact's own
  coordinates during resource filtering. `cashu-mint/0.32.0` moves with the build
  because it *is* the build.
- **Identity is configuration, and ships empty.** Name, pubkey, URLs, contacts
  and the rest come from the deployment. Their packaged defaults are blank, and a
  blank field is omitted from the response rather than filled in. A shipped
  default would be identical across every deployment, which is exactly the defect
  we were fixing.

## What the guard actually checks

`NutWiringContractTest` closes the loop in three directions, because drift has
three directions:

1. **The served map matches the registry.** The map is derived, so this mainly
   pins the assembler.
2. **Every declared NUT still has its wiring.** Each registry entry names a
   witness class, and optionally a member, resolved by reflection. Delete
   `DLEQProofGenerator.generateProof` and the build fails rather than leaving a
   claim about DLEQ proofs on the wire.
3. **Every wired NUT is declared.** The test scans the protocol packages for
   `@Nut`-annotated classes and requires each one to appear in the registry. This
   is the direction that would have caught NUT-19: implementing a NUT without
   advertising it now fails the build.

A separate test, `MintIdentityAdvertisementTest`, pins the retired placeholders
by value. If `Bob's Cashu mint`, `mint.host`, `Nutshell` or the old pubkey ever
reappear in a default response, that test fails and names the string.

## What this costs

Deriving the map is not free, and it is worth being honest about the trade.

**Adding a NUT now takes an extra step.** You must add a `NutSupport` constant
and pick a witness. That step is the point: it is a checkpoint where you state,
next to the code, what the mint is now promising.

**A witness is a proxy, not a proof.** That `P2PKSpendingCondition` exists on the
classpath does not prove it implements NUT-11 correctly. The registry guarantees
the advertisement tracks the code's *existence*, not its correctness.
Correctness is what the per-NUT behavioural tests are for, and where a NUT is
known to diverge (see the [compliance audit](nut-compliance-audit.md)) the fix
belongs in that NUT's own issue, not in a weakened advertisement.

**The melt fee reserve is still hand-set.** `fee_reserve_percent` comes from
`proto.properties` via `FeeConfig`, and while the melt path does apply it, the
advertised number and the applied number reach the response by different routes.
That is the remaining seam of this kind.

## The advertised limits are the enforced limits

`min_amount` and `max_amount` were the second half of the same defect, one layer
down. They moved out of the YAML into `mint.capabilities.*` properties, which
looked like progress, but nothing read them except the info endpoint. The mint
advertised `max_amount: 10000` and would happily issue a quote for a million.
The properties file even carried a comment saying the values "MUST match the
limits the deployment actually enforces" - a hand-maintained promise, which is
precisely what this work set out to eliminate.

The fix is not a second check that agrees with the first. Both the advertisement
and the enforcement now go through one object,
[`AmountLimitPolicy`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/AmountLimitPolicy.java):

- `DefaultMintInfoService` builds the NUT-04 and NUT-05 `methods` entries from
  `policy.mintMethods()` and `policy.meltMethods()`.
- `MintQuoteTask` calls `policy.requireWithinMintLimits` before it asks the
  gateway for an invoice; `MeltQuoteTask` calls `requireWithinMeltLimits` once
  the gateway has decoded the invoice amount.

They read the same `PaymentMethodLimits` instances, so the advertised number *is*
the enforced number. Raising `MINT_MAX_AMOUNT` raises both at once; there is no
second place to keep in step. An over-limit request is refused with
`amount_outside_limit_range`, and a unit the mint advertises no limits for at all
is refused with `unit_not_supported` rather than waved through.

Limits are matched on the unit rather than the payment method, because that is
what the amount is denominated in. A mint that will issue at most 10 000 sat
means that whichever rail carries the payment.

## What NUT-19 may claim

NUT-19's contract is narrow: a replayed request on a listed path returns the
*cached response*, not an error. The cached-endpoint list used to be a bare
`List.of(...)` of path strings, outside the witness mechanism entirely, and it
named `/v1/swap`. `SwapTask` has no response cache; a replayed swap is rejected
by `ValidateTransactionTask.rejectAlreadySignedOutputs` with
`outputs_already_signed`. A wallet that read `/v1/info`, saw `/v1/swap` listed as
cached, and safely retried an interrupted swap would have got an error instead of
its signatures.

Each route is now a
[`CachedEndpoint`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/CachedEndpoint.java)
constant naming the store it replays from: `/v1/mint/bolt11` witnessed by
`IssuanceRecord.signaturesJson`, `/v1/melt/bolt11` by
`MeltSaga.meltResponseCache`. A path cannot be advertised without naming a cache
that exists.

`/v1/swap` was dropped rather than given a cache. Both were on the table, and
narrowing the claim won on asymmetry of harm: under-advertising costs a wallet
one retry it could have made safely, while over-advertising costs it the retry it
did make. A swap cache is also not a small change - it needs a durable store
keyed on the outputs fingerprint and a replay path through the validator that
distinguishes "these outputs were signed for this same request" from "these
outputs were signed for a different one", which is the double-spend check itself.
Shipping the honest advertisement now and the cache when it can be done properly
is the safer order. When `SwapTask` gains that store, adding the enum constant
with it as the witness is all the advertisement needs.

## See also

- [Configure the mint](../how-to/configure-mint.md) - the identity and capability properties.
- [Add a NUT implementation](../how-to/add-a-nut.md) - where the registry step fits.
- [NUT compliance audit](nut-compliance-audit.md) - finding M8 and its milestone.
