# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) that packages the protocol, observability, and a Spring Boot REST API for running a mint.

## Modules

- `cashu-mint-protocol` – core Cashu protocol workflows, tasks, and vault/gateway integrations. See [Supported NUTs](#supported-nuts).
- `cashu-mint-jpa` – JPA persistence: quote, issuance, melt saga, voucher, swap hold, and webhook repositories, plus 20 Flyway migrations and the reconciliation and purge services.
- `cashu-mint-rest` – Spring Boot REST API that wires controllers to the protocol services and exposes actuator health/metrics.
- `cashu-mint-webhook` – webhook-based payment notifications for push-based payment status updates.
- `cashu-mint-observability` – Micrometer- and Actuator-based metrics, health indicators, and tracing hooks for the mint and gateway.
- `cashu-mint-tools` – deterministic preload generator for reproducible dev keysets.
- `cashu-mint-rest-it` – integration test harness for the REST module (voucher profile, H2, and Spring context tests).

## Supported NUTs

The authoritative list is the `NutSupport` enum in `cashu-mint-protocol`. It binds
each NUT to the class that implements it, and a contract test fails if the declared
visibility and the actual test-vector results ever disagree. **Update the enum, not
this table, when support changes**; the table below follows it.

| NUT | Name | Advertised in NUT-06 | Implementation |
|-----|------|----------------------|----------------|
| NUT-01 | Mint public keys | mandatory | `NUT01` |
| NUT-02 | Keysets | mandatory | `NUT02` |
| NUT-03 | Swap | payment methods | `NUT03` |
| NUT-04 | Mint tokens | payment methods | `NUT04` |
| NUT-05 | Melt tokens | payment methods | `NUT05` |
| NUT-06 | Mint info | mandatory | `NUT06` |
| NUT-07 | Token state check | simple | `NUT07` |
| NUT-08 | Overpaid melt fees | simple | `MeltTask` |
| NUT-09 | Restore signatures | simple | `NUT09` |
| NUT-10 | Well-known secrets | simple | `SpendingCondition` |
| NUT-11 | P2PK spending conditions | simple | `P2PKSpendingCondition` |
| NUT-12 | DLEQ proofs | simple | `DLEQProofGenerator` |
| NUT-17 | WebSocket subscriptions | websocket | `NUT17` |
| NUT-19 | Cached responses | cached responses | `MeltSaga` |
| NUT-20 | Signature on mint quote | simple | `MintQuoteSignature` (cashu-lib) |

Two entries carry history worth knowing:

- **NUT-11** was withheld until cashu-lib 0.24.0. The spending-condition logic was
  correct, but the NUT-10 secret it verified against was re-serialized into a
  non-spec shape, so no third-party wallet's proof could verify here and none of
  ours could verify elsewhere (cashu-lib#254).
- **NUT-20** is bound to its *verifier* rather than the request field, because a
  `pubkey` the mint accepts and never checks is exactly the false claim the enum
  exists to prevent.

### Admin modules (`cashu-mint-admin/`)

The `cashu-mint-admin` directory contains the administrative layer for managing mint lifecycle, configuration, users, and alerts:

- `mint-admin-core` – domain model, use case ports, services, and Flyway migrations.
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
curl http://localhost:9000/actuator/health/readiness
curl http://localhost:7777/v1/keysets
```

The dev stack seeds itself: at startup `VaultPreloadSeeder` writes the keyset in
`scripts/preload-test-data.json` into the shared vault, with the private keys going
to HashiCorp. The mint then reads its keysets back from that vault rather than from
the file, so a mint the admin provisions — and every rotation of its keyset — shows
up at `/v1/keysets`. Set `MINT_PRELOAD_ENABLED=true` to serve the file directly
instead, which decouples the mint from the admin and is rarely what you want.

## Observability

Metrics and health endpoints are enabled by default when `cashu-mint-observability` is on the classpath. The `cashu-mint-observability/docker/docker-compose.observability.yml` file starts Prometheus, Grafana, Loki, Alertmanager, and Jaeger for local dashboards:

```bash
docker compose -f cashu-mint-observability/docker/docker-compose.observability.yml up -d
```

Browse metrics at `http://localhost:9000/actuator/prometheus` and Grafana at `http://localhost:3000` (admin/admin). See `docs/how-to/enable-observability.md` and `cashu-mint-observability/docs/metrics-reference.md` for configuration and metric names.

## Payment notifications

Payment gateways push events to `/webhook/payment` instead of the mint polling for status. This reduces latency on mint requests and lowers gateway load. The mint falls back to polling when webhooks are unavailable. Added in 0.8.0. See [Payment webhook architecture](docs/explanations/payment-webhook-architecture.md) for details, and [Configure webhook integrity](docs/how-to/configure-webhook-integrity.md) for the mandatory shared secret behind the `PENDING → PAID` transition.

## WebSocket subscriptions (NUT-17)

Real-time WebSocket subscriptions per [NUT-17](https://github.com/cashubtc/nuts/blob/main/17.md). Clients can subscribe to proof and quote state changes and receive push notifications instead of polling. Added in 0.11.0.

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

## Signature persistence and wallet recovery

The protocol stores every blind signature produced during minting and swapping in a shared `SignatureVaultService`. This store is what makes wallet recovery ([NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md)) possible: a wallet that loses its local data can re-derive the same blinded messages and ask the mint to return the original signatures.

When using the REST API this is handled automatically — Spring injects a single `SignatureVaultService` bean into every controller. If you call protocol helpers like `NUT04.mint`, `NUT03.swap`, or `NUT09.restore` directly, you must pass the **same** `SignatureVaultService` instance to all of them so that signatures stored during minting can be retrieved during recovery.

## Security

The mint implements security controls aligned with the [Oracle Java Secure Coding Guidelines](https://www.oracle.com/java/technologies/javase/seccodeguide.html):

- **Immutable protocol classes** — NUT implementation classes are final with private constructors to prevent subclassing and ensure consistent behavior.
- **Input validation** — Webhook payloads, WebSocket messages, and REST inputs are validated with size limits and sanitized before processing.
- **Configurable rate limits** — Swap and mint operations enforce configurable maximum input/output counts via `SecurityLimits`.
- **Subscription limits** — WebSocket subscriptions are capped per session to prevent resource exhaustion.
- **Secure key derivation** — Lock managers use SHA-256 hashing for deterministic, collision-resistant key generation.
- **Defensive collections** — Internal maps and lists are wrapped as unmodifiable where exposed to prevent external mutation.
- **Sanitized exceptions** — Error messages exclude sensitive internal details to avoid information leakage.

See [Security measures](docs/explanations/security-measures.md) for the controls in place.

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
