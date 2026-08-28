# NUT compliance audit

This document records an audit of `cashu-mint`, `cashu-lib` and `cashu-wallet`
against the [cashubtc/nuts](https://github.com/cashubtc/nuts) specifications. It
explains what diverges, why each divergence matters, and where the work to close
it is tracked.

It is a snapshot, not a live status page. The issues it links to are the source
of truth for what has since been fixed.

**Audited at:** 2026-08-28
**Spec commit:** [`49a909ce4d0739824b3859d4b3da21e6c1abdaeb`](https://github.com/cashubtc/nuts/tree/49a909ce4d0739824b3859d4b3da21e6c1abdaeb)
**Repository versions:** cashu-mint 0.31.0, cashu-lib 0.21.0, cashu-wallet 0.6.4

## What the audit found

Fourteen divergences, across three categories.

**Interoperability.** Several defects mean our ecash and other implementations'
ecash cannot be exchanged. The most consequential is the secret encoding fed into
`hash_to_curve`: if our reading is wrong, every proof we have ever issued commits
to a different curve point than the one Nutshell or cashu-ts computes, and the
two systems are mutually unspendable. The error wire format and the quote
response shapes are lesser versions of the same problem — a wallet cannot parse
what we send.

**Protocol correctness.** NUT-11 `SIG_ALL` verifies each input and output
separately rather than signing one aggregated message. That is not a partial
implementation of `SIG_ALL`; it is a different scheme that does not deliver the
guarantee `SIG_ALL` exists to provide. Alongside it sit a set of missing
validations — duplicate inputs, mixed units, inactive keysets — each of which is
a named error code in the spec with no corresponding check in our code.

**Fees are not wired up at all.** `input_fee_ppk` is a field that is read but
never written. No configuration path sets it, the swap path enforces two
contradictory balance equations that only agree when the fee is zero, the
`/v1/keysets` listing does not publish the fee, and the wallet never reads it.
Four independent gaps, each of which alone would prevent charging fees.

Two items were carded during review rather than scheduled: keyset ID v2 and
NUT-20 signed mint quotes.

## How the audit was done

Each specification document was read against the implementing code, working from
the endpoint surface inward. Where a finding could be settled mechanically it
was: the `hash_to_curve` encoding question was resolved by reimplementing NUT-00
from the spec text and running the published test vectors under both candidate
encodings.

That exercise produced the audit's most important methodological finding. The
NUT-00 vectors pass under **both** readings, because their messages are raw byte
arrays printed as hex rather than realistic secrets. A vector suite would not
have caught the defect. Only an external implementation can, which is why an
interoperability test is filed as a finding in its own right rather than as
supporting work.

## Findings

### cashu-lib

| ID | Finding | NUT | Issue |
| --- | --- | --- | --- |
| L1 | `hash_to_curve` hex-decodes secrets instead of hashing their bytes | 00 | [#242](https://github.com/398ja/cashu-lib/issues/242) |
| L2 | Error responses use a non-spec wire format and no numeric codes | 00 | [#243](https://github.com/398ja/cashu-lib/issues/243) |
| L3 | Mint and melt quote responses missing required fields | 04, 05, 23 | [#244](https://github.com/398ja/cashu-lib/issues/244) |
| L4 | Input fees computed from a single keyset instead of per proof | 02 | [#245](https://github.com/398ja/cashu-lib/issues/245) |
| L5 | `/v1/keysets` entries omit `input_fee_ppk` and `final_expiry` | 02 | [#246](https://github.com/398ja/cashu-lib/issues/246) |
| L6 | Keyset ID v2 unsupported (**carded**, staying on v1) | 02 | [#247](https://github.com/398ja/cashu-lib/issues/247) |
| L7 | Only legacy BIP32 derivation, no version dispatch | 13 | [#248](https://github.com/398ja/cashu-lib/issues/248) |
| L8 | Official NUT test vectors not exercised in CI | all | [#249](https://github.com/398ja/cashu-lib/issues/249) |

### cashu-mint

| ID | Finding | NUT | Issue |
| --- | --- | --- | --- |
| M1 | `SIG_ALL` does not aggregate the transaction message | 11 | [#383](https://github.com/398ja/cashu-mint/issues/383) |
| M2 | Contradictory swap balance equations; any non-zero fee is unswappable | 02, 03 | [#384](https://github.com/398ja/cashu-mint/issues/384) |
| M3 | `input_fee_ppk` never set; make fees configurable in mint admin | 02 | [#385](https://github.com/398ja/cashu-mint/issues/385) |
| M4 | `/v1/checkstate` drops entries, ordering and witness data | 07 | [#386](https://github.com/398ja/cashu-mint/issues/386) |
| M5 | Missing input/output validations (duplicates, units, inactive keysets) | 03, 04, 05 | [#387](https://github.com/398ja/cashu-mint/issues/387) |
| M6 | Protocol errors default to HTTP 500 instead of 400 | 00 | [#388](https://github.com/398ja/cashu-mint/issues/388) |
| M7 | DLEQ generation failure silently returns an unproven signature | 12 | [#389](https://github.com/398ja/cashu-mint/issues/389) |
| M8 | `/v1/info` ships placeholder identity and misreports NUT support | 06 | [#390](https://github.com/398ja/cashu-mint/issues/390) |
| M9 | Signed mint quotes not implemented (**carded**) | 20 | [#391](https://github.com/398ja/cashu-mint/issues/391) |
| M10 | No interoperability test against an external implementation | all | [#392](https://github.com/398ja/cashu-mint/issues/392) |

### cashu-wallet

| ID | Finding | NUT | Issue |
| --- | --- | --- | --- |
| W1 | Wallet ignores `input_fee_ppk` and the keyset `active` flag | 02 | [#36](https://github.com/398ja/cashu-wallet/issues/36) |
| W2 | Missing DLEQ proof treated as successful verification | 12 | [#37](https://github.com/398ja/cashu-wallet/issues/37) |
| W3 | Melt change never requested or consumed, forfeiting the fee reserve | 08 | [#38](https://github.com/398ja/cashu-wallet/issues/38) |

## The three findings that matter most

### The secret encoding (L1)

`BDHKEUtils.hashToCurve(String)` hex-decodes any secret that does not begin with
`[`. NUT-00 describes hashing the bytes of the secret string, which for a
standard 64-character hex secret means its 64 ASCII bytes, not the 32 bytes it
decodes to. If that reading is right, no proof we issue can be verified anywhere
else.

The reason this is filed as a question rather than a fix is that the published
vectors cannot adjudicate it, and the blast radius of being wrong in either
direction is total. Resolving it needs a token from a reference implementation,
and if the encoding changes, every proof already issued needs a migration path.
The NUT-10 branch of the same method already does the right thing, which is a
hint that the `else` branch is the accident.

### `SIG_ALL` (M1)

The spec's `SIG_ALL` signs one message covering every input and every output,
carried in the first input's witness. Ours signs each input separately and each
output's `B_` separately, and requires a witness on all of them.

The practical difference: because our per-output signature does not commit to the
other outputs, outputs can be reordered or substituted and the signatures still
verify. The output `amount` is not covered at all. On the melt path the quote id
is not covered either, so a witness captured from one melt is not bound to the
quote it paid.

The distinct-key threshold counting in the same class is careful, correct work
and should survive the rewrite — the bug is in what gets signed, not in how
signatures are counted.

### Fees (L4, L5, M2, M3, W1)

Fees are the clearest example of a gap that looks small per-file and is total in
aggregate. Following one swap through:

1. Nothing sets `input_fee_ppk`, so it is always `0` (M3).
2. If it were set, `/v1/keysets` would not publish it, so no wallet could see it (L5).
3. If a wallet could see it, it does not read it (W1).
4. If it read it, the fee would be priced off the first input's keyset for every
   input, which is wrong whenever a swap consolidates an old keyset — the routine
   case, since NUT-02 keeps inactive keysets spendable as inputs (L4).
5. And if all of that were fixed, the swap would still fail, because
   `VerifyProofsTask` demands `inputs == outputs` while `VerifyFeesTask` demands
   `inputs - fees == outputs`, and both run (M2).

M2 also surfaced an ordering problem worth more than the fee question: swap
validation runs *after* the blinded messages are signed, so a rejected swap
leaves signatures in the signature vault, retrievable through NUT-09 restore.

## What is right

The audit read a lot of correct code, and the compliance table above does not
show it. Worth recording, both for morale and because these are the parts a fix
must not regress:

- **BDHKE and DLEQ mathematics.** The blind-signature scheme, the DLEQ challenge
  construction, and `hash_e`'s uncompressed-hex-then-UTF-8 hashing all match the
  spec. The NUT-00 vectors pass.
- **NUT-09 restore.** Paired `outputs`/`signatures` arrays, gaps skipped so
  wallets can detect them, and no dependency on how the secret was derived. The
  implementation and its documentation are both accurate.
- **NUT-11 threshold counting.** Counting distinct public keys by x-coordinate
  rather than matching signature pairs, with the parity prefix stripped before
  BIP-340 verification. The comment explaining why is the kind of comment worth
  having: it records a decision that is not obvious from the code.
- **NUT-07 per-mint state lookup.** `CheckStateTask` iterates the request,
  defaults unknown proofs to `UNSPENT`, and populates the witness. Every defect
  in M4 is introduced by the cross-mint merge layer above it, not by this.
- **The melt saga.** Burn-before-pay ordering, compare-and-set state transitions,
  and a deliberate refusal to auto-resolve a `PAYMENT_UNKNOWN` payment. That last
  choice — leaving a stuck payment for a human — is the right call and is
  documented as such.

## Sequencing

The findings are not independent. Roughly:

1. **`cashu-lib` first.** The error format (L2), the fee resolver (L4) and the
   keyset fields (L5) are all prerequisites for mint-side work. Vendoring the
   test vectors (L8) should come before any crypto change, so there is a
   regression net.
2. **Settle L1 early**, because if the encoding changes, it invalidates
   assumptions everywhere else and needs a migration plan more than a patch. The
   interop test (M10) is what settles it.
3. **Mint protocol layer:** M2 and M5 together (both are "validate properly,
   before signing"), then M1, then M7.
4. **Mint REST layer:** M6 depends on L2. M8 depends on M1 and M7 being done, or
   on deciding to advertise less.
5. **Wallet last**, mirroring the library changes.

Two things are worth doing regardless of order, because they change how
confidently everything else can be done: the vendored vectors (L8) and the
interop test (M10).

## Deliberate divergences

Not everything that differs from the spec is a defect. The following are
intentional and should not be re-raised by a future audit:

- **Vouchers** (`VOUCHER` well-known secrets, `/v1/vouchers`, percentage-fee
  quotes) are an extension with no NUT, documented in
  [Representing vouchers as structured secrets](voucher-structured-secrets.md).
- **Model B melt refusal** — vouchers are rejected at melt by design, redeemable
  only with the issuing merchant.
- **Zero-value IOU keysets**, which refuse swap and melt.
- **Multi-mint endpoints**, where a single deployment fronts several mints and
  `/v1/swap` infers which from the inputs' keyset id.

These would ideally each carry an ADR, so the reasoning survives independently of
this document.

## See also

- [Supported NUTs](../reference/nuts.md) - per-NUT implementation status
- [Architecture and NUTs](architecture-and-nuts.md) - module-to-spec mapping
- [Error codes](../reference/error-codes.md) - current REST error codes
- [Add a NUT implementation](../how-to/add-a-nut.md)
