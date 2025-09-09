# REST API

This reference lists REST endpoints exposed by the mint. All endpoints are rooted at `/v1`.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/keys/{mint_id}/generate` | Generate keyset IDs for a mint. |
| `GET` | `/keys/keyset/{keyset_id}` | Retrieve keys for a specific keyset. |
| `GET` | `/keysets` | List active keysets. |
| `POST` | `/swap/{mint_id}` | Swap tokens within a mint. |
| `POST` | `/mint/quote/{method}` | Request a mint quote for a payment method. |
| `GET` | `/mint/quote/{method}/{quote_id}` | Check status of a mint quote. |
| `POST` | `/mint/{mintId}/{method}` | Mint tokens using a payment method. |
| `POST` | `/melt/quote/{method}` | Request a melt quote for a payment method. |
| `GET` | `/melt/quote/{method}/{quote_id}` | Check status of a melt quote. |
| `POST` | `/melt/{mint_id}/{method}` | Melt tokens using a payment method. |
| `GET` | `/info` | Retrieve mint information. |
| `POST` | `/checkstate/{mint_id}` | Check state of tokens against a mint. |
