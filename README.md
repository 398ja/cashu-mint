# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) that packages the protocol, observability, and a Spring Boot REST API for running a mint.

The protocol exposes a shared `SignatureVaultService` so signatures minted in one request can be restored later. When calling `NUT04.mint`, `NUT09.restore`, or other protocol helpers directly, pass the same `SignatureVaultService` instance to persist signatures across requests.

## Modules

- `cashu-mint-protocol` – core Cashu protocol workflows (NUT-01/02/03/04/05/06/07/09), tasks, and vault/gateway integrations.
- `cashu-mint-rest` – Spring Boot REST API that wires controllers to the protocol services and exposes actuator health/metrics.
- `cashu-mint-observability` – Micrometer- and Actuator-based metrics, health indicators, and tracing hooks for the mint and gateway.
- `cashu-mint-tools` – deterministic preload generator and SQL renderer for seeding the vault with reproducible keysets.
- `cashu-mint-rest-it` – integration test harness for the REST module (voucher profile, H2, and Spring context tests).

Administrative APIs live in the separate [`cashu-mint-admin`](https://github.com/cashubtc/cashu-mint-admin) project. The Compose files in this repo reference the published `cashu-mint-admin-rest` image but no admin code ships here.

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

## Docs and tooling

- Documentation follows the Diátaxis structure in `docs/README.md`.
- Gateway mapping overrides are documented in `docs/how-to/configure-gateways.md`.
- Preload generation commands live in `cashu-mint-tools` (see `docs/reference/tools.md`).

Run the full build before opening a PR:

```bash
./mvnw -q verify
```
