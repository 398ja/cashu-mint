# Configuration

## Prerequisites

The project requires **Java 21** for build and runtime. The Maven Enforcer plugin rejects other JDK versions. Ensure `JAVA_HOME` points to a JDK 21 installation, or configure a Maven Toolchain with JDK 21.

## Properties reference

Default values are sourced from `cashu-mint-rest/src/main/resources/application.properties`, `cashu-mint-rest/src/main/resources/application-voucher.yml`, and `cashu-mint-protocol/src/main/resources/proto.properties`. Override any property with an environment variable (uppercase, dots to underscores) or a `-D` system property.

## Core server settings

| Property | Default | Description |
| --- | --- | --- |
| `server.port` | `${CASHU_MINT_PORT:7777}` | HTTP port for the REST API. |
| `management.server.port` | `${CASHU_MINT_MANAGEMENT_PORT:9000}` | Actuator port. Separate from the API port so metrics are not publicly readable. |
| `management.server.address` | `${CASHU_MINT_MANAGEMENT_ADDRESS:127.0.0.1}` | Interface actuator binds to. Containers set `0.0.0.0` and leave the port unpublished. |
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
- `gateway.bolt11.sat=xyz.tcheeric.payment.adapter.ln.phoenixd.PhoenixdGateway`
- `gateway.bolt11=xyz.tcheeric.payment.adapter.ln.phoenixd.PhoenixdGateway`

Keep NUT-06 methods/units (`mint.capabilities.*`, below) aligned with the gateway mappings you configure.

## Mint identity (NUT-06)

Every field below is empty by default and an unset field is **omitted** from `/v1/info` rather than filled with a placeholder. Set them per deployment; two deployments of this codebase must not claim the same identity.

| Property | Environment variable | Description |
| --- | --- | --- |
| `mint.identity.name` | `MINT_NAME` | Display name shown to wallets. |
| `mint.identity.pubkey` | `MINT_PUBKEY` | The mint's public key. Leave unset until the deployment has one to publish. |
| `mint.identity.description` | `MINT_DESCRIPTION` | Short description. |
| `mint.identity.description-long` | `MINT_DESCRIPTION_LONG` | Long description. |
| `mint.identity.motd` | `MINT_MOTD` | Message of the day. |
| `mint.identity.icon-url` | `MINT_ICON_URL` | Icon URL. |
| `mint.identity.tos-url` | `MINT_TOS_URL` | Terms of service URL. |
| `mint.identity.urls` | `MINT_URLS` | Comma-separated public base URLs. |
| `mint.identity.contacts` | `MINT_CONTACTS` | Comma-separated `method:info` pairs, e.g. `email:ops@example.com,nostr:npub1...`. |

The NUT-06 `version` is **not** configurable. It is `cashu-mint/<project version>`, filled in by the Maven build, so the advertised version always matches the running artifact.

## Advertised capabilities (NUT-06)

Which NUTs appear in the `nuts` map is derived from the code (`NutSupport`) and is not configurable. Only the operational numbers a deployment legitimately varies live here, and they **must** match the limits the deployment actually enforces.

| Property | Default | Description |
| --- | --- | --- |
| `mint.capabilities.mint-methods[n].method` | `bolt11` | Advertised mint payment method. |
| `mint.capabilities.mint-methods[n].unit` | `sat` | Advertised mint unit. |
| `mint.capabilities.mint-methods[n].min-amount` | `1` | Minimum mint amount. |
| `mint.capabilities.mint-methods[n].max-amount` | `10000` | Maximum mint amount. |
| `mint.capabilities.melt-methods[n].*` | as above | Same shape for melt. |
| `mint.capabilities.mint-disabled` | `false` | Advertise minting as disabled. |
| `mint.capabilities.melt-disabled` | `false` | Advertise melting as disabled. |
| `mint.capabilities.cached-response-ttl` | `PT15M` | NUT-19 `ttl`; how long a cached response stays replayable. |

See [why /v1/info is derived from the wiring](../explanations/mint-info-advertisement.md).

## Preload and vault seeding

| Property | Default | Description |
| --- | --- | --- |
| `mint.preload.enabled` | `true` | Enable the preload-based `MintLoadService` (dev/test). |
| `mint.preload.json.input` | `scripts/preload-test-data.json` | Path to the preload JSON used to seed the vault and expose keysets. |

The dev Docker Compose stack mounts `scripts/preload-test-data.json`; the mint seeds that keyset into the vault at startup.

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
- Actuator is served on `management.server.port` (default `9000`), **not** the public API
  port. Nothing under `/actuator/**` answers on `server.port`. Point scrapers and container
  health checks at the management port. `ManagementPortGuard` fails startup if the two ports
  are ever set equal, so the separation cannot be undone by configuration alone.
- Isolation comes from **not publishing** the management port, not from the bind address.
  `management.server.address` defaults to `0.0.0.0` so that Prometheus can scrape from a
  sibling container and Kubernetes `httpGet` probes — which the kubelet issues against the
  pod IP, never loopback — succeed. Do not set it to `127.0.0.1` in Kubernetes: pods would
  never become ready. Set it to `127.0.0.1` only when running the jar directly on a host
  where you want loopback-only access.
- `docker-compose.dev.yml` publishes the management port on host loopback
  (`127.0.0.1:9000:9000`) so local `curl localhost:9000/actuator/...` works.
  `docker-compose.prod.yml` deliberately does not publish it at all.
- `management.endpoints.web.exposure.include=health,info,prometheus,metrics`
- `management.prometheus.metrics.export.enabled=true`
- `management.endpoint.health.probes.enabled=true`
- Histogram buckets for request/task/crypto latency are pre-configured under `management.metrics.distribution.slo.*`.

## Trace producer (spec 036)

Emits signed `kind-9079` trace events to the `cashu-ledger` forensic ledger. Disabled by default;
see [Enable the trace producer](../how-to/enable-trace-producer.md) for the full guide.

| Property | Default | Description |
| --- | --- | --- |
| `cashu.trace.publisher.enabled` | `false` | Master switch. When `false` no trace beans are created and mint behaviour is unchanged. |
| `cashu.trace.publisher.private-key-hex` | _(empty)_ | Producer signing key (secret — env only). Boot fails closed if enabled and blank. |
| `cashu.trace.publisher.relays` | _(empty)_ | Comma-separated ledger relays. Boot fails closed if enabled and empty. |
| `cashu.trace.publisher.outbox-jdbc-url` | `jdbc:sqlite::memory:` | Durable outbox + operation-id registry. Use a file path in production for restart survival. |
| `cashu.mint.url` | _(empty)_ | Mint identity events are attributed to. Required (and boot fails closed) when tracing is enabled. |

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
  - `GATEWAY_BOLT11_SAT=xyz.tcheeric.payment.adapter.ln.dummy.DummyGateway docker compose --profile dev up`
- Serve keysets from the shared vault rather than the preload JSON:
  - `MINT_PRELOAD_ENABLED=false ./mvnw -pl cashu-mint-rest spring-boot:run`
