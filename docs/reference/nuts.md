# Supported NUTs

This reference lists the NUT specifications implemented in the repository, their implementation classes, and current status.

| NUT | Title | Spec | Implementation | Status |
|-----|-------|------|----------------|--------|
| NUT-00 | Notation, Utilization, and Terminology | [00.md](https://github.com/cashubtc/nuts/blob/main/00.md) | N/A (conventions) | Implemented |
| NUT-01 | Mint public key exchange | [01.md](https://github.com/cashubtc/nuts/blob/main/01.md) | `NUT01.java` | Implemented |
| NUT-02 | Keysets and keyset ID | [02.md](https://github.com/cashubtc/nuts/blob/main/02.md) | `NUT02.java` | Implemented |
| NUT-03 | Swap tokens | [03.md](https://github.com/cashubtc/nuts/blob/main/03.md) | `NUT03.java`, `SwapTask` | Implemented |
| NUT-04 | Mint tokens | [04.md](https://github.com/cashubtc/nuts/blob/main/04.md) | `NUT04.java`, `MintTask` | Implemented |
| NUT-05 | Melt tokens | [05.md](https://github.com/cashubtc/nuts/blob/main/05.md) | `NUT05.java`, `MeltTask` | Implemented |
| NUT-06 | Mint information | [06.md](https://github.com/cashubtc/nuts/blob/main/06.md) | `NUT06.java` | Implemented |
| NUT-07 | Token state check | [07.md](https://github.com/cashubtc/nuts/blob/main/07.md) | `NUT07.java` | Implemented |
| NUT-09 | Restore signatures | [09.md](https://github.com/cashubtc/nuts/blob/main/09.md) | `NUT09.java` | Implemented |
| NUT-12 | DLEQ proofs | [12.md](https://github.com/cashubtc/nuts/blob/main/12.md) | via `cashu-lib-crypto` | Implemented |
| NUT-17 | WebSocket subscriptions | [17.md](https://github.com/cashubtc/nuts/blob/main/17.md) | `NUT17.java`, `WebSocketHandler` | Implemented |

Implementation classes are located in `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/`.

## See Also

- [REST API reference](rest-api.md)
- [Architecture and NUTs](../explanations/architecture-and-nuts.md)
- [Glossary](glossary.md)
