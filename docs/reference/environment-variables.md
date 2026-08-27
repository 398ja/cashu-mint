# Environment variables

This reference lists the environment variables used to configure the Cashu mint and its supporting services.

## Mint REST API

| Variable | Default | Description |
|----------|---------|-------------|
| `CASHU_MINT_PORT` | `7777` | HTTP port for the mint REST API |
| `SERVER_PORT` | `7777` | Spring Boot server port (alias) |
| `SPRING_PROFILES_ACTIVE` | — | Active Spring profiles (e.g., `dev`, `staging`, `voucher`) |
| `LOG_LEVEL_ROOT` | `INFO` | Root log level |
| `LOG_LEVEL_SPRING` | `INFO` | Spring framework log level |
| `LOG_LEVEL_CASHU` | `DEBUG` | Project log level |

## Vault

| Variable | Default | Description |
|----------|---------|-------------|
| `CASHU_VAULT_BASE_URL` | — | Base URL of the vault service |
| `VAULT_BASE_URL` | — | Alias for `CASHU_VAULT_BASE_URL` |
| `CASHU_VAULT_PORT` | `3333` | HTTP port for the vault service |
| `CASHU_VAULT_VERSION` | `latest` | Docker image version for vault |

## Gateway and payments

| Variable | Default | Description |
|----------|---------|-------------|
| `GATEWAY_BOLT11_SAT` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Gateway class for BOLT11 sat payments |
| `GATEWAY_BOLT11` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Fallback gateway class for BOLT11 |
| `GATEWAY_API_BASE_URL` | — | Base URL of the payment adapter REST service |
| `GATEWAY_CLIENT_CONNECT_TIMEOUT` | `5s` | HTTP connect timeout for gateway calls |
| `GATEWAY_CLIENT_READ_TIMEOUT` | `30s` | HTTP read timeout for gateway calls |
| `GATEWAY_WALLET_MOCK_PAYMENT_ENABLED` | `false` | Enable mock payments (dev only) |
| `GATEWAY_WALLET_MOCK_PAYMENT_PHOENIXD_URL` | — | URL for the phoenixd mock service |
| `PAYMENT_ADAPTER_PORT` | `8080` | Port for the payment adapter REST service |
| `PAYMENT_ADAPTER_WEBHOOK_PORT` | `9090` | Port for the payment adapter webhook service |

## Phoenixd (Lightning)

| Variable | Default | Description |
|----------|---------|-------------|
| `PHOENIXD_BASE_URL` | — | Base URL of the Phoenixd Lightning service |
| `PHOENIXD_API_KEY` | — | API key for Phoenixd authentication |
| `PHOENIXD_API_TOKEN` | — | API token for Phoenixd (dev mode) |
| `PHOENIXD_PORT` | `9740` | Phoenixd service port |
| `PHOENIXD_LNADDRESS` | — | Lightning address mode (`off` to disable) |

## Webhooks

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBHOOK_URL` | — | URL where payment notifications are sent |
| `WEBHOOK_SECRET` | — | HMAC-SHA256 secret for webhook signature validation |
| `WEBHOOK_ENABLED` | `true` | Enable push-based payment notifications |
| `WEBHOOK_WID` | `phoenixd` | Phoenixd webhook identifier |
| `MINT_WEBHOOK_SECRET` | — | Webhook secret (used in compose files) |

## Voucher

| Variable | Default | Description |
|----------|---------|-------------|
| `VOUCHER_QUOTE_FEE_PERCENT` | `10` | Percentage fee for voucher mint quotes |
| `VOUCHER_MASTER_SECRET` | _(auto-generated)_ | Hex master secret for voucher key derivation |

## Admin

| Variable | Default | Description |
|----------|---------|-------------|
| `CASHU_MINT_ADMIN_PORT` | `7778` | HTTP port for the admin REST API |
| `CASHU_MINT_ADMIN_SUPER_ADMIN_NPUB` | _(none)_ | npub of the admin Super Administrator |
| `CASHU_MINT_ADMIN_EXTERNAL_BASE_URL` | `http://localhost:7778` | Audience admin handshake proofs must name |
| `CASHU_MINT_ADMIN_VERSION` | `latest` | Docker image version for admin |
| `CASHU_MINT_ADMIN_WEB_PORT` | `3000` | Port for the admin web UI |

## Docker image versions

| Variable | Default | Description |
|----------|---------|-------------|
| `CASHU_MINT_VERSION` | `latest` | Mint REST Docker image version |
| `CASHU_VAULT_VERSION` | `latest` | Vault JPA Docker image version |
| `PHOENIXD_VERSION` | `latest` | Phoenixd mock Docker image version |
| `PAYMENT_ADAPTER_WEBHOOK_VERSION` | `latest` | Payment adapter webhook Docker image version |

## See also

- [Configuration](configuration.md) — application properties and defaults
- [Configure the mint](../how-to/configure-mint.md) — overriding configuration at runtime
- [Configure gateways](../how-to/configure-gateways.md) — mapping methods/units to gateway classes
