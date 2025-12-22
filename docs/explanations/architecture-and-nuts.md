# Architecture and NUTs

This explanation outlines the responsibilities of each module and how the Cashu NUT specifications map to the current implementation.

## Module responsibilities
- **cashu-mint-protocol** – core protocol logic for keysets, token issuance, swaps, melts, voucher quotes, and state checks. This module holds the business rules and cryptographic operations shared by every surface.
- **cashu-mint-rest** – REST interface that delegates to the protocol, infers mint ids from proofs/keysets, and exposes actuator endpoints. See the [REST API reference](../reference/rest-api.md) for details.
- **cashu-mint-observability** – Micrometer/Actuator auto-configuration that instruments protocol tasks, HTTP requests, vault/gateway health, and optional tracing.

## Supported NUTs

| NUT | Description | Implementation |
| --- | --- | --- |
| [NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md) | Notation, Utilization, and Terminology | Shared terminology across modules. |
| [NUT-01](https://github.com/cashubtc/nuts/blob/main/01.md) | Mint public key exchange | Key retrieval via `/v1/keys/keyset/{keyset_id}` backed by protocol keyset services. |
| [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md) | Keysets and keyset ID | Managed in `cashu-mint-protocol`; exposed over HTTP via `/v1/keysets`. |
| [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md) | Swap tokens | `/v1/swap` infers the mint from input proofs and delegates to swap tasks. |
| [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md) | Mint tokens | Mint quotes (`/v1/mint/quote/{method}`) and issuance (`/v1/mint/{method}`) backed by protocol mint tasks; voucher mint quotes use the same flow. |
| [NUT-05](https://github.com/cashubtc/nuts/blob/main/05.md) | Melt tokens | Melt quotes (`/v1/melt/quote/{method}`) and melts (`/v1/melt/{method}`) backed by protocol melt tasks. |
| [NUT-06](https://github.com/cashubtc/nuts/blob/main/06.md) | Mint information | Exposed via `/v1/info`, with legacy helper fields appended for compatibility. |
| [NUT-07](https://github.com/cashubtc/nuts/blob/main/07.md) | Token state check | `/v1/checkstate` merges state across active and archived mints. |
| [NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md) | Restore signatures | `/v1/restore` reuses the shared `SignatureVaultService` to return prior signatures. |
