# cashu-mint

## Modules
- `cashu-mint-protocol` – core protocol implementation
- `cashu-mint-rest` – REST API exposing wallet endpoints

## Configuration properties
| Property | Default | Description |
| --- | --- | --- |
| `cashu_mint_port` | `7777` | Port for the mint server; override with system property or `CASHU_MINT_PORT` env var. |
| `cashu.units` | `sat` | Unit used for amounts. |
| `cashu.expiry` | `15` | Token expiry time in minutes. |
| `cashu.gateway` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Payment gateway implementation class. |
| `cashu.melt.fee-reserve-percent` | `0.05` | Fraction added as fee reserve during melts. |

## REST API
All endpoints are rooted at `/v1`.

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

## Docker Compose
To run the services with Docker Compose select a profile and set
`PHOENIXD_SERVICE` to match it:

- `docker compose --profile dev up` starts the `phoenixd-mock` service for
  local development. Set `PHOENIXD_SERVICE=phoenixd-mock` before running the
  command. This profile does not require any Phoenixd credentials.
- `docker compose --profile prod up` starts the real `phoenixd-rest` service
  for production usage. Set `PHOENIXD_SERVICE=phoenixd-rest` and provide a real
  `PHOENIXD_API_KEY` environment variable. Optionally set `PHOENIXD_WALLET_SEED`
  and `PHOENIXD_DATA_DIR` to choose where the Phoenixd wallet data is stored;
  by default it uses `$HOME/.phoenixd`.

When invoking Docker with `sudo`, pass these variables explicitly or use
`sudo -E` so the home directory of the calling user is preserved.

## Supported NUTs
- [NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md): Notation, Utilization, and Terminology
- [NUT-01](https://github.com/cashubtc/nuts/blob/main/01.md): Mint public key exchange
- [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md): Keysets and keyset ID
- [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md): Swap tokens
- [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md): Mint tokens
- [NUT-05](https://github.com/cashubtc/nuts/blob/main/05.md): Melt tokens
- [NUT-06](https://github.com/cashubtc/nuts/blob/main/06.md): Mint information
- [NUT-07](https://github.com/cashubtc/nuts/blob/main/07.md): Token state check
- [NUT-10](https://github.com/cashubtc/nuts/blob/main/10.md): Spending conditions
- [NUT-11](https://github.com/cashubtc/nuts/blob/main/11.md): Pay to Public Key (P2PK)

## License
This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.

## Disclaimer
This project is a work in progress and is not yet ready for production use. Use at your own risk.
