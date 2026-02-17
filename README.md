# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) that packages the protocol, observability, and a Spring Boot REST API for running a mint.

The protocol exposes a shared `SignatureVaultService` so signatures minted in one request can be restored later. When calling `NUT04.mint`, `NUT09.restore`, or other protocol helpers directly, pass the same `SignatureVaultService` instance to persist signatures across requests.

## Modules

- `cashu-mint-protocol` – core Cashu protocol workflows (NUT-01/02/03/04/05/06/07/09/12/17), tasks, and vault/gateway integrations.
- `cashu-mint-rest` – Spring Boot REST API that wires controllers to the protocol services and exposes actuator health/metrics.
- `cashu-mint-webhook` – webhook-based payment notifications for push-based payment status updates.
- `cashu-mint-observability` – Micrometer- and Actuator-based metrics, health indicators, and tracing hooks for the mint and gateway.
- `cashu-mint-tools` – deterministic preload generator and SQL renderer for seeding the vault with reproducible keysets.
- `cashu-mint-rest-it` – integration test harness for the REST module (voucher profile, H2, and Spring context tests).

### Admin modules (`cashu-mint-admin/`)

The `cashu-mint-admin` directory contains the administrative layer for managing mint lifecycle, configuration, users, and alerts:

- `mint-admin-core` – domain model, use case ports, services, and Flyway migrations.
- `mint-admin-cli` – Picocli command-line interface with stub and HTTP adapters.
- `mint-admin-rest` – Spring Boot REST API (port 7778).
- `mint-admin-web` – React/TypeScript web admin interface.
- `mint-admin-tests` – integration and E2E test harnesses.

See [`cashu-mint-admin/docs/README.md`](cashu-mint-admin/docs/README.md) for admin-specific documentation. The Docker Compose files in this repo include the published `cashu-mint-admin-rest` image in the dev stack.

## Run the stack locally

Start the development stack (mint REST API, vault, gateway, Phoenixd mock, admin REST image):

```bash
docker compose --profile dev up
```

The public API listens on `http://localhost:7777/v1`. Check readiness and minted keysets:

```bash
curl http://localhost:7777/actuator/health/readiness
curl http://localhost:7777/v1/keysets
```

Use the preload JSON (`scripts/preload-test-data.json`) and SQL (`scripts/preload-test-data.sql`) when running locally—the dev profile seeds the vault and configures the REST service to read the same keyset from disk.

## Observability

Metrics and health endpoints are enabled by default when `cashu-mint-observability` is on the classpath. The `cashu-mint-observability/docker/docker-compose.observability.yml` file starts Prometheus, Grafana, Loki, Alertmanager, and Jaeger for local dashboards:

```bash
docker compose -f cashu-mint-observability/docker/docker-compose.observability.yml up -d
```

Browse metrics at `http://localhost:7777/actuator/prometheus` and Grafana at `http://localhost:3000` (admin/admin). See `docs/how-to/enable-observability.md` and `cashu-mint-observability/docs/metrics-reference.md` for configuration and metric names.

## Payment notifications

Version 0.8.0 introduces webhook-based payment notifications. Payment gateways push events to `/webhook/payment` instead of the mint polling for status. This reduces latency on mint requests and lowers gateway load. The mint falls back to polling when webhooks are unavailable. See [Payment webhook architecture](docs/explanations/payment-webhook-architecture.md) for details.

## WebSocket subscriptions (NUT-17)

Version 0.11.0 adds real-time WebSocket subscriptions per [NUT-17](https://github.com/cashubtc/nuts/blob/main/17.md). Clients can subscribe to proof and quote state changes and receive push notifications instead of polling.

**Endpoint:** `ws://localhost:7777/v1/ws`

**Supported subscription kinds:**
- `proof_state` — Notifies when proofs transition between UNSPENT, PENDING, and SPENT
- `bolt11_mint_quote` — Notifies when mint quotes change state (UNPAID → PAID → ISSUED)
- `bolt11_melt_quote` — Notifies when melt quotes change state (UNPAID → PENDING → PAID)

**Configuration:**
```properties
# Enable/disable WebSocket subscriptions (default: true)
cashu.websocket.enabled=true

# Allowed origins for CORS (default: * — restrict in production)
cashu.websocket.allowed-origins=https://your-app.com
```

**Example subscription (JSON-RPC 2.0):**
```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "method": "subscribe",
  "params": {
    "kind": "proof_state",
    "filters": [{"ids": ["proof-y-value-1", "proof-y-value-2"]}]
  }
}
```

New subscribers receive the current state of subscribed items immediately, then real-time updates as states change.

## Security

The mint implements security controls aligned with the [Oracle Java Secure Coding Guidelines](https://www.oracle.com/java/technologies/javase/seccodeguide.html):

- **Immutable protocol classes** — NUT implementation classes are final with private constructors to prevent subclassing and ensure consistent behavior.
- **Input validation** — Webhook payloads, WebSocket messages, and REST inputs are validated with size limits and sanitized before processing.
- **Configurable rate limits** — Swap and mint operations enforce configurable maximum input/output counts via `SecurityLimits`.
- **Subscription limits** — WebSocket subscriptions are capped per session to prevent resource exhaustion.
- **Secure key derivation** — Lock managers use SHA-256 hashing for deterministic, collision-resistant key generation.
- **Defensive collections** — Internal maps and lists are wrapped as unmodifiable where exposed to prevent external mutation.
- **Sanitized exceptions** — Error messages exclude sensitive internal details to avoid information leakage.

See `audits/AUDIT_REPORT_java-secure-coding-guidelines.md` for the full compliance report.

## Documentation

Documentation is organized using the [Diataxis framework](https://diataxis.fr/) under [`docs/`](docs/README.md):

- **Tutorials** — Getting started, Docker Compose, WebSocket client example
- **How-to guides** — Development workflow, testing, deployment, gateway adapters, troubleshooting
- **Reference** — REST API, error codes, glossary, configuration, environment variables, NUT implementations
- **Explanations** — Architecture, voucher system, webhooks, virtual threads, security

See [`docs/reference/glossary.md`](docs/reference/glossary.md) for Cashu and ecash terminology.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for branch naming, commit conventions, PR process, and code standards.

Run the full build before opening a PR:

```bash
./mvnw -q verify
```
