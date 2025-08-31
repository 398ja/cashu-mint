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
| `gateway.prod` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Production gateway implementation class. |
| `gateway.mock` | `xyz.tcheeric.gateway.mock.PhoenixdMockGateway` | Mock gateway used for development and tests. |
| `cashu.melt.fee-reserve-percent` | `0.05` | Fraction added as fee reserve during melts. |

The gateway used by the protocol is chosen via the `PROFILE` (or `ENV`) environment variable.
If `PROFILE` is set to `prod`, the class defined in `gateway.prod` is instantiated; otherwise the
`gateway.mock` implementation is used. When running with Docker Compose, start the matching
Compose profile (for example `docker compose --profile prod up`) and set `PROFILE=prod` to use the
real Phoenixd gateway.

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
The `PHOENIXD_SERVICE` environment variable controls which Phoenixd backend is
used. It defaults to `phoenixd-mock` for local development. For production,
set `PHOENIXD_SERVICE=phoenixd-rest`.

- `docker compose --profile dev up` starts the `phoenixd-mock` service for
  local development. This profile does not require any Phoenixd credentials.
- `docker compose --profile prod up` starts the real `phoenixd-rest` service
  for production usage. Set `PHOENIXD_SERVICE=phoenixd-rest` and provide a real
  `PHOENIXD_API_KEY` environment variable. Optionally set `PHOENIXD_WALLET_SEED`
  and `PHOENIXD_DATA_DIR` to choose where the Phoenixd wallet data is stored.
  By default it uses `$HOME/.phoenixd`.

Docker Compose uses pre-built images hosted at `docker.398ja.xyz`. Run
`docker compose --profile dev pull` (or `prod`) to fetch the latest images
before starting the services.

Example commands:

```bash
docker compose --profile dev up
PHOENIXD_SERVICE=phoenixd-rest PHOENIXD_API_KEY=your-key \
  docker compose --profile prod up
```

When invoking Docker with `sudo`, pass these variables explicitly or use
`sudo -E` so the home directory of the calling user is preserved.

## Docker Image Publishing
The project uses the Jib Maven plugin to publish the `cashu-mint-rest` Docker image
to `docker.398ja.xyz/cashu-mint-rest`. Each release is tagged with both the
current project version and `latest`.

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
