# Why the signature vault is durable

This explains why the mint records every blind signature it issues in the database, how a
second signature on the same output is refused, and what that means for retries, restarts,
and more than one mint instance (issue #491).

## What the vault is for

The signature vault maps a blinded message (`B_`) to the blind signature (`C_`) the mint
issued for it. Two things depend on it:

- **Refusing to sign an output twice.** `ValidateTransactionTask` refuses any output the
  vault already holds with `outputs_already_signed` (NUT error code 11003).
- **NUT-09 restore.** A wallet recovering from its seed re-derives its blinded messages
  (NUT-13) and asks the mint for the signatures back. The vault is where they come from.

## The problem with an in-memory vault

The only implementation used to be a `ConcurrentHashMap`. After a restart the mint had
forgotten every output it had signed, so:

- a client could submit an already-signed `B_` again, with new valid inputs, and get a second
  signature on it. That produces two identical proofs of which only one can ever be spent,
  so issued value and spendable value drift apart;
- NUT-09 restore returned nothing for outputs signed before the restart, so a wallet
  recovering from its seed lost funds the mint did issue;
- with two replicas, each had its own map, so each could sign an output the other had signed.

## The durable vault

With `cashu.mint.jpa.enabled=true`, `JpaSignatureVaultService` records each signature in the
`blind_signature` table:

| Column | Meaning |
|---|---|
| `b_` | The blinded message, compressed point hex. Primary key. |
| `keyset_id` | The signing keyset. Sized for NUT-02 v2 ids (66 hex chars). |
| `amount` | Face value. Zero is allowed for the zero-value IOU keyset; negative is not. |
| `c_` | The blind signature. |
| `dleq_e`, `dleq_s` | The NUT-12 DLEQ proof, so restore returns it as first issued. Both or neither. |
| `source` | `MINT`, `SWAP` or `MELT_CHANGE`: why the signature exists. |
| `created_at` | When it was recorded. |

Rows are never updated or deleted. Every mint instance on the database shares them, and they
survive restarts.

## How a duplicate is refused

`store` is a plain `INSERT` in its own transaction. The primary key on `b_` decides: of two
requests racing to sign the same output, on one instance or on two, exactly one insert
commits and the other is refused with `outputs_already_signed`. Only a row already present
under the same `b_` is reported that way. Any other constraint failure is an
`internal_error`, so a wallet is never told a mint fault means its output was signed.

The pre-signing check in `ValidateTransactionTask` still runs first and catches almost every
duplicate before any work is done. The insert is the backstop for the race that check cannot
see.

The in-memory vault kept for local and test contexts now refuses duplicates the same way. It
used to log and keep the first signature, which let tests pass on behaviour production no
longer has.

## Retries still work

Refusing duplicates must not break the idempotent retries the mint relies on. None of them
re-sign:

- **NUT-19 mint replay.** A retry with the same outputs against an issued quote is answered
  from the `issuance_record` row without signing, so it never reaches the vault.
- **Melt.** NUT-08 change is signed once after the saga reaches `COMPLETED`, and a replayed
  melt is answered from the saga's response cache.
- **Swap.** Swap is not advertised under NUT-19. A replayed swap was already refused with
  `outputs_already_signed` by `ValidateTransactionTask`; the durable vault makes that true
  after restarts too.

## Two orderings the refusal forced

Moving the refusal to the moment of recording, where a concurrent request can trigger it,
exposed two places that assumed signing could not fail that way.

**Mint must refuse before it consumes the quote.** `MintTask` moves a paid quote from `PAID`
to `ISSUING` before it signs. A refusal after that move would strand the customer's payment
in `ISSUING` with nothing issued. So the vault is checked while the quote is still `PAID`
(or `FUNDED` for a voucher), and a refused request leaves it there for the customer to try
again with fresh outputs.

**A partially signed swap must spend its inputs.** A swap signs its outputs one at a time
under a hold on its inputs, and used to release the hold on any signing failure. If the
second output is refused after the first was recorded, the first is already recoverable
through NUT-09 restore, and releasing the inputs would let the same value be redeemed twice.
So `SwapTask` releases the hold only when nothing was recorded, and otherwise commits it, as
it does when signing completes. See [Why validation runs before signing](validation-before-signing.md)
for the ordering this extends.

## Wiring, and why the fallback is not a `@Service`

The in-memory `DefaultSignatureVaultService` used to be a component-scanned `@Service`. Making
a component-scanned bean conditional on another bean's absence is unreliable, because the
scanner registers beans in classpath order and the condition may be checked before the
durable vault exists.

It is now registered by `SignatureVaultFallbackAutoConfiguration`, which runs after
`MintJpaAutoConfiguration` and after all component scanning, and only when no other
`SignatureVaultService` is defined. When JPA is enabled the context holds exactly one vault,
the durable one.

## Production refuses the in-memory vault

`SignatureVaultStartupValidator` fails startup outside the `local`, `test` and
`websocket-test` profiles when the wired vault reports `isDurable() == false`. It checks the
bean, not the `cashu.mint.jpa.enabled` property, so it also holds when
`cashu.mint.jpa.require-in-production=false` waives the durable-persistence guard. It has no
waiver of its own.

## The issued-amount metric

Because the record is durable, the mint can say how much it has issued. The invariant poller
exports `cashu_mint_issued_amount_total{keyset}`, the sum of `amount` per keyset. This is the
issued side of an issued-versus-backed reconciliation. It never decreases, and it is re-derived
from the table on every poll, so a restart does not reset it. See the
[metrics reference](../../cashu-mint-observability/docs/metrics-reference.md).
