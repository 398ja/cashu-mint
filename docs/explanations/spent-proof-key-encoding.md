# Spending a proof exactly once across the secret encoding change

The mint's defence against double spending is a single stored value per proof: the curve point
`Y = hash_to_curve(secret)`. cashu-lib 0.22.0 changed how that point is computed. This explains why
that change is dangerous, what the mint does about it, and when the workaround can be removed.

## The store is keyed on Y, not on the secret

When a proof is spent the mint writes a row keyed on `Y`, not on the secret string. `Y` is a fixed
66-character hex value whatever the secret looks like, so a 64-character random secret and a
NUT-10 well-known secret (a JSON array of arbitrary length) produce keys of the same shape.

Every double-spend check is therefore the same question: *is there a row under this proof's Y?*

## Why the same proof now computes a different Y

[NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md) defines `Y = hash_to_curve(x)` over the
UTF-8 bytes of the secret message. cashu-lib previously hex-decoded any secret that was not a
well-known secret and hashed the resulting 32 bytes instead. For a realistic 64-character hex
secret those are two different inputs, so they are two different points.

cashu-lib 0.22.0 corrected this, and models the two encodings as `SecretEncoding.SPEC` and
`SecretEncoding.LEGACY_HEX` (see ADR 0001 in cashu-lib).

The consequence for this mint is sharper than a compatibility problem. A proof spent before the
upgrade has a row under its **legacy** point. After the upgrade the mint computes the **spec**
point for that same proof, finds no row, and concludes the proof is unspent. The proof is then
spendable a second time. Nothing in the compiler or the type system catches this: the code is
unchanged, only the value it computes moved.

## What the mint does

The two questions the store has to answer are separated, and both live in
`SpentProofKey`:

- **On lookup**, `SpentProofKey.lookupKeys(secret)` returns every point the proof could be recorded
  under, in `SecretEncoding.verificationOrder()`: the spec point first, then the legacy point.
  `DefaultProofVaultService.retrieveProof` queries them in that order and only concludes "unspent"
  after all of them miss.
- **On insert**, `DefaultProofVaultService.storageKeyFor` returns the point an existing record
  already uses, and otherwise the spec point. A proof that has always been keyed on the legacy
  point keeps that key when its spend is written, so one logical proof never becomes two rows.

Signature verification needs no equivalent machinery: every `C = k*Y` check goes through
`BDHKEUtils.verify(String secret, ...)`, which already walks the same encoding order.

An encoding that cannot be applied to a given secret contributes no key. `LEGACY_HEX` does not
support a secret that is neither hex nor well-known, so the fallback never widens a lookup beyond
the two points a proof could genuinely have been issued under. For a well-known secret both
encodings agree and the lookup collapses to a single query.

## Why order rather than detection

The encoding a proof was issued under is not recorded on the wire, so it cannot be read off the
proof. A defined order is the only available discriminator. Putting the spec encoding first means
the legacy path is touched only by genuinely old proofs, and costs a second query only when the
first misses.

## The legacy population drains itself

No new proof is ever recorded under the legacy point: `SecretEncoding.forIssuance()` is always
`SPEC`. Legacy proofs are spendable exactly once more, and the outputs of that spend are issued
under the spec encoding. The legacy population therefore only shrinks.

`LEGACY_HEX` and the dual lookup can be deleted once an operator confirms no unspent legacy proof
remains. Until then, removing either one reopens the double-spend hole.

## How this is held in place

`LegacyProofDoubleSpendTest` is the standing instrument. Its central case stores a spent proof
under the legacy point only, looks it up after the upgrade, and asserts the mint still reports it
SPENT. If the dual lookup is ever removed that test fails rather than the hole reopening silently.

## Related

- [NUT compliance audit](nut-compliance-audit.md), finding L1
- [Run the interoperability test](../how-to/run-the-interoperability-test.md)
