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

**The operational numbers are still hand-set.** `min_amount`, `max_amount` and
the melt fee reserve come from deployment properties, and nothing forces them to
match the limits the mint actually enforces. They are genuinely
deployment-specific, so configuration is the right home, but this is the seam
where the next drift will appear. If those limits ever become enforced in code,
they should be derived from the enforcing component the same way the NUT list is.

## See also

- [Configure the mint](../how-to/configure-mint.md) - the identity and capability properties.
- [Add a NUT implementation](../how-to/add-a-nut.md) - where the registry step fits.
- [NUT compliance audit](nut-compliance-audit.md) - finding M8 and its milestone.
