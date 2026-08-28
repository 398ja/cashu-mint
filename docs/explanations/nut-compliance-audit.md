# NUT compliance audit

This document records an audit of `cashu-mint`, `cashu-lib` and `cashu-wallet`
against the [cashubtc/nuts](https://github.com/cashubtc/nuts) specifications. It
explains what diverges, why each divergence matters, where the work to close it
is tracked, and in what order that work should be done.

The findings are a snapshot; the linked issues are the source of truth for what
has since been fixed. The [implementation plan](#implementation-plan) is the part
intended to be acted on.

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

## Implementation plan

The findings are not independent, and the dependency that shapes everything is
release order: `cashu-mint` consumes `cashu-lib` as a published artifact
(`${cashu-lib.version}`), so any mint-side work that needs a library change waits
on a `cashu-lib` release. The milestones below are ordered so that each one ends
at a releasable, coherent state rather than at an arbitrary commit boundary.

Two constraints worth stating up front, because they explain the ordering:

- **`cashu-wallet` does not block the mint.** The mint declares
  `cashu-wallet-protocol` and `cashu-wallet-client` in `dependencyManagement` but
  no module consumes them, so wallet work can proceed in parallel with everything
  else.
- **The swap path has no transaction boundary.** `SwapTask` calls no
  `@Transactional` method, so the fix for signing-before-validating (M2) is to
  reorder the steps, not to add a rollback. That keeps M2 much cheaper than it
  first looks.

### Milestone 0 — Establish the evidence base

**Findings:** L8, M10
**Repos:** cashu-lib, cashu-mint
**Releases:** none

Nothing else should be attempted first. Every subsequent milestone changes
cryptographic or wire-format behaviour, and today there is no regression net and
no way to tell whether a change helps or hurts interoperability.

1. Vendor the NUT test vectors at pinned commit `49a909c` into `cashu-lib` and
   drive them from parameterized tests (NUT-00, 01, 02, 11, 12, 13). **(L8)**
2. Stand up an interoperability test against an external implementation
   (`cashu-ts` or Nutshell) driving mint → swap → melt. The `payment-adapter-ln-dummy`
   adapter and the phoenixd mock already exist, so this needs no real Lightning
   node for the mint and swap legs. **(M10)**
3. Record which properties the vectors **cannot** pin down, starting with the
   `hash_to_curve` secret encoding.

**Exit criteria:** vectors run in `mvn verify` and fail the build on a mismatch;
the interop test executes end to end, whether or not it currently passes. A
failing interop test here is a successful milestone — it is the instrument, not
the result.

### Milestone 1 — Settle the secret encoding

**Findings:** L1
**Repos:** cashu-lib (+ cashu-mint if the encoding changes)
**Releases:** `cashu-lib` minor, or major if the encoding changes

Isolated into its own milestone because it is the one finding whose answer
changes the shape of everything after it, and because the answer is not yet
known.

1. Use the Milestone 0 interop test to determine which encoding actually
   interoperates: mint a token externally, and check whether our `verify` accepts
   it under hex-decoding, UTF-8, or neither.
2. Record the answer in this document and in an ADR.
3. If the current behaviour is wrong, treat it as a **migration, not a patch**:
   already-issued proofs must keep verifying under the legacy encoding while new
   ones use the spec encoding. That dual-path requirement reaches into
   `cashu-mint`'s verification, so it is not contained in the library.

**Exit criteria:** the encoding question is answered with evidence from a
reference implementation, and either closed as a non-issue or landed with a
migration path.

**Decision gate:** if the encoding must change, everything downstream is affected
and the remaining milestones should be re-estimated before starting Milestone 2.

### Milestone 2 — Library foundations

**Findings:** L2, L4, L5, L3
**Repos:** cashu-lib
**Releases:** `cashu-lib` minor (breaking for error-format consumers)

The prerequisites for all mint-side work, grouped because they release together.

1. **Error format (L2).** One `ErrorResponse` emitting `{"detail": <str>, "code": <int>}`,
   serialized by Jackson rather than `String.format`, plus a `CashuErrorCode` enum
   carrying the `error_codes.md` numeric codes. Keep the existing string keys as
   enum constant names so downstream `switch` sites keep compiling.
2. **Per-proof fee resolution (L4).** Replace `getFees(KeySet)` with a resolver
   keyed on each proof's own keyset id, raising `12001` for an unknown keyset
   instead of using `assert`.
3. **Keyset listing fields (L5).** Add `input_fee_ppk` and `final_expiry` to the
   `/v1/keysets` entry type.
4. **Quote response fields (L3).** Add the NUT-04/05/23 fields, including a melt
   quote `state` that can express `PENDING`.

**Exit criteria:** `cashu-lib` released; the error format has a round-trip test
proving the mint's output is what the client parses.

**Coordination note:** L2 changes a wire format that `imani-gateway` and the
admin also consume. Those consumers need to be checked before this releases.

### Milestone 3 — Mint transaction integrity

**Findings:** M2, M5
**Repos:** cashu-mint
**Releases:** `cashu-mint` minor

Grouped because they are one change described twice: *validate properly, and do
it before signing*. Splitting them would mean touching `SwapTask` twice.

1. Remove the balance check from `VerifyProofsTask.validateAmounts`, leaving one
   equation: `sum(inputs) - fees == sum(outputs)`. **(M2)**
2. Move fee-aware validation **before** the signing loop, so a rejected swap
   leaves no signatures in the vault. This is the security-relevant half: today a
   rejected swap's signatures are retrievable through NUT-09 restore. **(M2)**
3. Extract the protocol validations into one step shared by swap/mint/melt:
   duplicate inputs (`11007`), duplicate outputs (`11008`), mixed units
   (`11009`/`11010`), inactive-keyset outputs (`12002`), already-signed outputs
   (`11003`). **(M5)**

**Exit criteria:** a test asserts the signature vault is untouched after a
rejected swap; a doubled input cannot inflate the output sum; a fee-bearing
keyset swaps successfully.

**Sequencing note:** the duplicate-input check should be written as a test
first — whether it is exploitable end to end depends on `ProofLockManager`
behaviour that has not been confirmed either way.

### Milestone 4 — NUT-11 SIG_ALL

**Findings:** M1
**Repos:** cashu-mint
**Releases:** `cashu-mint` minor

Alone in its milestone: it is the most intricate change, it is self-contained,
and it carries a compatibility risk the others do not.

1. Extract message aggregation into its own type, with `forSwap(inputs, outputs)`
   and `forMelt(inputs, quoteId, blankOutputs)`.
2. Enforce the uniformity precondition (all inputs same kind, flag, `data`,
   `tags` when any input is `SIG_ALL`).
3. Verify the aggregated message against the **first input's** witness only.
4. Thread the quote id into the melt path, closing the replay gap where a witness
   is not bound to the quote it pays.

**Exit criteria:** NUT-11 vectors pass; reordering an output invalidates the
signature; a witness from one melt quote fails against another.

**Before starting:** confirm no deployed proof relies on the current
non-standard `SIG_ALL` behaviour. Fixing this correctly invalidates any such
proof, because the signed message changes. Dalia phase 9 escrow uses
`SIG_INPUTS`, which is unaffected — but that needs verifying, not assuming.

### Milestone 5 — Mint surface honesty

**Findings:** M6, M4, M7, M8
**Repos:** cashu-mint
**Releases:** `cashu-mint` minor

What the mint tells the outside world about itself. Grouped because each one is
"the response does not match what the spec or our own capability says", and
because M8 can only be settled once the others land.

1. **HTTP status mapping (M6).** Protocol errors default to `400`. Give the error
   code its status as an attribute, deleting the `switch`, the message
   substring-matching and the `Throwable.detailMessage` reflection. *Depends on L2.*
2. **`/v1/checkstate` (M4).** Key the cross-mint merge on the requested `Y` list
   so the response matches the request in length and order, carry the witness
   through, and consult archived mints unconditionally.
3. **DLEQ fail-closed (M7).** Stop returning an unproven signature when proof
   generation fails; add the deterministic nonce per NUT-12.
4. **`/v1/info` (M8).** Real identity from deployment config, version from the
   build, advertise NUT-19, and a test that fails when the advertised `nuts` map
   disagrees with what is wired.

**Exit criteria:** a double-spend attempt returns `400` with the spec error body;
`states.length == Ys.length` always; every swap/mint response carries a `dleq`;
no placeholder identity survives.

### Milestone 6 — Fees end to end

**Findings:** M3, W1
**Repos:** cashu-mint (admin), cashu-wallet
**Releases:** `cashu-mint` minor, `cashu-wallet` minor

Deliberately last of the functional milestones. Fees cannot work until L4, L5 and
M2 have all landed, and turning them on before then takes the mint down.

1. **Admin fee configuration (M3).** Set `input_fee_ppk` when provisioning a
   keyset, persist it, serve it, and record changes in the Audit Trail.
2. **Decide fee-change-vs-rotation** and capture it in an ADR. Under keyset ID v2
   a fee change is a new keyset by definition; deciding now keeps us aligned for
   later.
3. **Wallet fee handling (W1).** A keyset cache tracking `active` and
   `input_fee_ppk`; outputs built only from active keysets; fees subtracted from
   outputs; input selection preferring inactive-keyset proofs.

**Exit criteria:** an end-to-end test mints, then swaps against a fee-bearing
keyset, and the mint's balance reflects the collected fee.

### Milestone 7 — Wallet correctness

**Findings:** W2, W3
**Repos:** cashu-wallet
**Releases:** `cashu-wallet` minor

Independent of the mint, and can run in parallel with Milestones 3–5.

1. **DLEQ policy (W2).** A missing proof from a mint advertising NUT-12 is a
   failure; make "verified" and "no proof present" distinguishable by the caller.
2. **Melt change (W3).** Construct `ceil(log2(fee_reserve))` blank outputs and
   unblind the returned change. This is a direct, recurring loss of user funds
   today, so it is the highest-value item in the wallet.

**Exit criteria:** a melt whose routing fee is below the reserve recovers the
difference, asserted against the balance.

### Deferred

**L6** (keyset ID v2) and **M9** (NUT-20 signed mint quotes) are carded, and
**L7** (NUT-13 version dispatch) is blocked on L6. They are not scheduled here.

L7 has one piece worth doing early regardless: make derivation dispatch on the
keyset version byte and fail loudly on a v2 keyset, rather than silently deriving
v1 secrets and recovering nothing. Silent recovery failure is indistinguishable
from an empty wallet, which is the worst failure mode available.

### Critical path

The chain that determines total elapsed time:

```
M0 ──> M1 ──> M2 ──> M3 ──> M4 ──> M5 ──> M6
```

Everything on that line is blocked by what precedes it. Off the critical path:

- **M7 (wallet correctness)** can start immediately. W2 and W3 need nothing from
  any other milestone.
- **W1**, the wallet half of M6, is the one wallet item that waits — it needs the
  fee resolver from M2 and something to read a fee from.
- **M3 → M4 → M5** are sequenced because they touch overlapping code
  (`SwapTask`, the validator package, the error handler), not because of a
  logical dependency. Given separate people they could overlap, at the cost of
  merge conflicts.

M6 is last because it is the only milestone that *enables* a behaviour rather
than correcting one, and switching fees on before M2 and M3 have landed would
take the mint down.

### What this plan does not do

It does not schedule the deliberate divergences below, and it does not treat the
compliance table as a to-do list to be driven to zero. Two findings (L6, M9) are
open decisions rather than defects, and closing them without deciding first would
be worse than leaving them open.

It also assigns no dates or estimates. The one real unknown — the encoding
question in M1 — can change the size of everything after it, so estimating past
that gate before it is answered would be inventing numbers.

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
