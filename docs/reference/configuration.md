# Configuration

## Prerequisites

The project requires **Java 21** for build and runtime. The Maven Enforcer plugin rejects other JDK versions. Ensure `JAVA_HOME` points to a JDK 21 installation, or configure a Maven Toolchain with JDK 21.

## Properties reference

Default values are sourced from `cashu-mint-rest/src/main/resources/application.properties`, `cashu-mint-rest/src/main/resources/application-voucher.yml`, and `cashu-mint-protocol/src/main/resources/proto.properties`. Override any property with an environment variable (uppercase, dots to underscores) or a `-D` system property.

## Core server settings

| Property | Default | Description |
| --- | --- | --- |
| `server.port` | `${CASHU_MINT_PORT:7777}` | HTTP port for the REST API. |
| `server.address` | `0.0.0.0` | Bind address for the REST API. |
| `cashu.units` | `sat` | Default unit advertised in NUT-06. |
| `cashu.expiry` | `15` | Token expiry window in minutes (protocol default). |
| `cashu.melt.fee-reserve-percent` | `0.05` | Percentage of melt amount reserved for fees. |
| `webhook.wid` | `${WEBHOOK_WID:phoenixd}` | Phoenixd webhook identifier. |

## Gateway selection

Gateway implementations can be configured per method or per method+unit. Environment variables have the highest precedence:
- `GATEWAY_<METHOD>_<UNIT>` (for example `GATEWAY_BOLT11_SAT`)
- `GATEWAY_<METHOD>` (for example `GATEWAY_BOLT11`)

Fallbacks come from `rest.properties` and `proto.properties`:
- `gateway.bolt11.sat=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway`
- `gateway.bolt11=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway`

Keep NUT-06 (`mint.yaml`) methods/units aligned with the gateway mappings you configure.

## Preload and vault seeding

| Property | Default | Description |
| --- | --- | --- |
| `mint.preload.enabled` | `true` | Enable the preload-based `MintLoadService` (dev/test). |
| `mint.preload.json.input` | `scripts/preload-test-data.json` | Path to the preload JSON used to seed the vault and expose keysets. |

The dev Docker Compose profile mounts `scripts/preload-test-data.json` and seeds the vault using `scripts/preload-test-data.sql`.

## WebSocket subscriptions (NUT-17)

| Property | Default | Description |
| --- | --- | --- |
| `cashu.websocket.enabled` | `true` | Enable NUT-17 WebSocket endpoint at `/v1/ws`. |
| `cashu.websocket.allowed-origins` | `*` | Comma-separated CORS origins. Restrict in production. |

## Payment webhooks

| Property | Default | Description |
| --- | --- | --- |
| `webhook.enabled` | `true` | Enable push-based payment notifications. |
| `webhook.secret` | _(unset)_ | HMAC-SHA256 secret for signature validation. |
| `webhook.cache.ttl` | `PT15M` | TTL for payment status cache entries. |

## Voucher minting (voucher profile)

Activate the voucher profile with `SPRING_PROFILES_ACTIVE=voucher` to expose `/v1/vouchers`. Required properties come from `application-voucher.yml`:

| Property | Default | Description |
| --- | --- | --- |
| `voucher.enabled` | `true` (when profile active) | Enable voucher endpoints. |
| `voucher.mint.issuerPrivateKey` | _(unset)_ | Hex-encoded ED25519 private key for signing vouchers. |
| `voucher.mint.issuerPublicKey` | _(unset)_ | Hex-encoded ED25519 public key paired with the issuer private key. |
| `voucher.nostr.relays` | `wss://relay.damus.io`, `wss://relay.cashu.xyz` | Default relay list for voucher ledger operations. |
| `voucher.quote.fee-percent` | `10` | Percentage fee charged when creating voucher mint quotes. Override with `VOUCHER_QUOTE_FEE_PERCENT`. |
| `voucher.quote.fee-percent.max` | `100` | Maximum allowed fee percentage. |
| `voucher.master.secret` | _(auto-generated)_ | Hex-encoded master secret for voucher key derivation. If unset, a secure random secret is generated at startup. Override with `VOUCHER_MASTER_SECRET`. |

## Observability

| Property | Default | Description |
| --- | --- | --- |
| `cashu.observability.enabled` | `true` | Global toggle for metrics/health/tracing auto-config. |
| `cashu.observability.tasks.enabled` | `true` | Enable task timing AOP instrumentation. |
| `cashu.observability.metrics.track-keysets` | `true` | Track per-keyset counters (set to false to reduce cardinality). |
| `cashu.observability.metrics.include-unit-tag` | `true` | Include `unit` labels on business metrics. |
| `cashu.observability.vouchers.enabled` | `true` | Collect voucher metrics. |
| `cashu.observability.gateway.enabled` | `true` | Collect gateway metrics. |
| `cashu.observability.health.gateway.enabled` | `true` | Enable gateway health indicator. |
| `cashu.observability.health.gateway.timeout-ms` | `5000` | Gateway health check timeout (ms). |
| `cashu.observability.health.vault.enabled` | `true` | Enable vault health indicator. |
| `cashu.observability.health.vault.timeout-ms` | `5000` | Vault health check timeout (ms). |
| `cashu.observability.tracing.enabled` | `false` | Export traces via OTLP when true. |
| `cashu.observability.tracing.endpoint` | `http://localhost:4317` | OTLP endpoint for traces. |
| `cashu.observability.tracing.service-name` | `cashu-mint` | Service name tag for tracing. |
| `cashu.observability.tracing.environment` | `development` | Environment tag for tracing. |
| `cashu.observability.tracing.sampling-ratio` | `1.0` | Sampling ratio (0.0–1.0). |

Actuator exposure defaults:
- `management.endpoints.web.exposure.include=health,info,prometheus,metrics`
- `management.prometheus.metrics.export.enabled=true`
- `management.endpoint.health.probes.enabled=true`
- Histogram buckets for request/task/crypto latency are pre-configured under `management.metrics.distribution.slo.*`.

## Logging

| Property | Default | Description |
| --- | --- | --- |
| `logging.level.root` | `${LOG_LEVEL_ROOT:INFO}` | Root log level. |
| `logging.level.org.springframework` | `${LOG_LEVEL_SPRING:INFO}` | Spring log level. |
| `logging.level.xyz.tcheeric.cashu` | `${LOG_LEVEL_CASHU:DEBUG}` | Project log level. |
| `logging.pattern.console` | `%d{yyyy-MM-dd HH:mm:ss.SSS} %5p [%t] %-40.40logger{39} : %m%n` | Console pattern. |

## Example overrides

- Change the port:
  - `CASHU_MINT_PORT=8888 ./mvnw -pl cashu-mint-rest spring-boot:run`
- Switch the Bolt11 gateway at runtime:
  - `GATEWAY_BOLT11_SAT=xyz.tcheeric.gateway.dummy.DummyGateway docker compose --profile dev up`
- Run without preload seeding:
  - `MINT_PRELOAD_ENABLED=false ./mvnw -pl cashu-mint-rest spring-boot:run`
