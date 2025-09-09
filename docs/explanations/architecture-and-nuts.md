# Architecture and NUTs

This explanation outlines the responsibilities of each module and how Cashu NUT specifications map to the current implementation.

## Module responsibilities
- **cashu-mint-protocol** – core protocol logic for keysets, token issuance, swaps, melts and state checks. This module contains the business rules and cryptographic operations.
- **cashu-mint-rest** – REST interface exposing the mint over HTTP. See the [REST API reference](../reference/rest-api.md) for endpoint details.

## Supported NUTs
The project supports the following NUT specifications.

| NUT | Description | Implementation |
| --- | --- | --- |
| [NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md) | Notation, Utilization, and Terminology | Provides shared terminology across modules. |
| [NUT-01](https://github.com/cashubtc/nuts/blob/main/01.md) | Mint public key exchange | Key generation and retrieval via `cashu-mint-rest` endpoints backed by `cashu-mint-protocol` keyset services. |
| [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md) | Keysets and keyset ID | Managed in `cashu-mint-protocol`; exposed over HTTP by `cashu-mint-rest`. |
| [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md) | Swap tokens | `POST /swap/{mint_id}` in `cashu-mint-rest` using swap logic from `cashu-mint-protocol`. |
| [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md) | Mint tokens | Mint quotes and issuance handled by both modules. |
| [NUT-05](https://github.com/cashubtc/nuts/blob/main/05.md) | Melt tokens | Melting endpoints in `cashu-mint-rest` backed by protocol redemption services. |
| [NUT-06](https://github.com/cashubtc/nuts/blob/main/06.md) | Mint information | Exposed via `/info` endpoint. |
| [NUT-07](https://github.com/cashubtc/nuts/blob/main/07.md) | Token state check | `/checkstate/{mint_id}` endpoint with protocol verification. |
| [NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md) | Restore signatures | `/restore` endpoint delegates to protocol restore task (NUT09). |
| [NUT-10](https://github.com/cashubtc/nuts/blob/main/10.md) | Spending conditions | Enforced by condition handling in `cashu-mint-protocol`. |
| [NUT-11](https://github.com/cashubtc/nuts/blob/main/11.md) | Pay to Public Key (P2PK) | Supported through P2PK conditions in `cashu-mint-protocol`. |
