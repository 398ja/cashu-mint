# Supported NUTs

The authoritative list is the **`NutSupport` enum** in
`cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NutSupport.java`.
It binds each NUT to the class that implements it and declares how the NUT appears
in the NUT-06 `nuts` map.

A contract test resolves each binding by reflection and fails if the declared
visibility and the actual test-vector results disagree, in either direction. That
makes the enum self-checking: it cannot claim support the code does not have, and
it cannot understate support the vectors prove.

**When support changes, update the enum.** This page follows it.

## Implemented

| NUT | Title | Spec | Implementation | NUT-06 visibility |
|-----|-------|------|----------------|-------------------|
| NUT-00 | Notation and terminology | [00.md](https://github.com/cashubtc/nuts/blob/main/00.md) | conventions, plus cashu-lib token codecs | n/a |
| NUT-01 | Mint public key exchange | [01.md](https://github.com/cashubtc/nuts/blob/main/01.md) | `NUT01` | mandatory |
| NUT-02 | Keysets and keyset ID | [02.md](https://github.com/cashubtc/nuts/blob/main/02.md) | `NUT02` | mandatory |
| NUT-03 | Swap tokens | [03.md](https://github.com/cashubtc/nuts/blob/main/03.md) | `NUT03`, `SwapTask` | payment methods |
| NUT-04 | Mint tokens | [04.md](https://github.com/cashubtc/nuts/blob/main/04.md) | `NUT04`, `MintTask` | payment methods |
| NUT-05 | Melt tokens | [05.md](https://github.com/cashubtc/nuts/blob/main/05.md) | `NUT05`, `MeltTask` | payment methods |
| NUT-06 | Mint information | [06.md](https://github.com/cashubtc/nuts/blob/main/06.md) | `NUT06` | mandatory |
| NUT-07 | Token state check | [07.md](https://github.com/cashubtc/nuts/blob/main/07.md) | `NUT07` | simple |
| NUT-08 | Overpaid melt fees | [08.md](https://github.com/cashubtc/nuts/blob/main/08.md) | `MeltTask` | simple |
| NUT-09 | Restore signatures | [09.md](https://github.com/cashubtc/nuts/blob/main/09.md) | `NUT09` | simple |
| NUT-10 | Well-known secrets | [10.md](https://github.com/cashubtc/nuts/blob/main/10.md) | `SpendingCondition` | simple |
| NUT-11 | P2PK spending conditions | [11.md](https://github.com/cashubtc/nuts/blob/main/11.md) | `P2PKSpendingCondition` | simple |
| NUT-12 | DLEQ proofs | [12.md](https://github.com/cashubtc/nuts/blob/main/12.md) | `DLEQProofGenerator` | simple |
| NUT-17 | WebSocket subscriptions | [17.md](https://github.com/cashubtc/nuts/blob/main/17.md) | `NUT17`, `WebSocketHandler` | websocket |
| NUT-19 | Cached responses | [19.md](https://github.com/cashubtc/nuts/blob/main/19.md) | `MeltSaga` response cache | cached responses |
| NUT-20 | Signature on mint quote | [20.md](https://github.com/cashubtc/nuts/blob/main/20.md) | `MintQuoteSignature` (cashu-lib), enforced by `MintTask` | simple |

Protocol classes live in
`cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/`. Some NUTs
are implemented by a task or validator rather than a `NUTxx` class, which the enum
records explicitly.

## Entries with history

### NUT-11 was withheld until cashu-lib 0.24.0

The spending-condition logic was correct throughout. The problem was one layer
down: the NUT-10 secret it verified against was re-serialized into a non-spec
shape, so **no third-party wallet's proof could verify here and none of ours could
verify elsewhere** (cashu-lib#254).

Advertising support in that state would have been worse than not advertising it,
since a wallet would have had every reason to expect interoperability. That the
entry now reads `simple` is not a judgement call: the named vector suite decides
it, and the contract test fails if visibility and vectors ever disagree again.

### NUT-20 binds to the verifier, not the request field

The enum points at `MintQuoteSignature.isValid`, the code that checks the
signature, rather than at the `pubkey` field on the request.

A `pubkey` the mint accepts and never checks is exactly the false claim the enum
exists to prevent: the request would look NUT-20 shaped while the quote stayed a
bearer token. Binding to the verifier means the claim cannot be made unless the
verification exists.

See the [cashu-lib NUT-20 reference](https://github.com/398ja/cashu-lib/blob/main/docs/reference/nut20-mint-quote-signature.md)
for the message encoding.

### NUT-12 is fail-closed

DLEQ proof generation uses a deterministic nonce and fails closed. See
[DLEQ fail-closed](../explanations/dleq-fail-closed.md).

## NUT-06 visibility

The `Visibility` enum controls how a NUT appears in the `nuts` map returned by
`/v1/info`:

| Visibility | Meaning |
|------------|---------|
| `MANDATORY` | Required by the spec; never listed in the map |
| `SIMPLE` | Listed as a simple supported flag |
| `PAYMENT_METHODS` | Listed with its supported method/unit pairs |
| `WEBSOCKET` | Listed with supported subscription kinds |
| `CACHED_RESPONSES` | Listed with the cached-response paths |

## See also

- [REST API reference](rest-api.md)
- [Architecture and NUTs](../explanations/architecture-and-nuts.md)
- [Add a NUT implementation](../how-to/add-a-nut.md)
- [Glossary](glossary.md)
