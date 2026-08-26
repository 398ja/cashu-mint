# How to Enable Observability

This guide explains how to enable and configure the observability stack for Cashu Mint, including Prometheus metrics, Grafana dashboards, alerting, log aggregation, and distributed tracing.

## Prerequisites

- Docker and Docker Compose installed
- Cashu Mint running (development or production)

## Quick Start

### 1. Start the Observability Stack

First, ensure the mint is running:

```bash
docker compose -f docker-compose.dev.yml up -d
```

Then start the full observability stack:

```bash
docker compose -f cashu-mint-observability/docker/docker-compose.observability.yml up -d
```

### 2. Access Dashboards

- **Prometheus**: http://localhost:9090
- **Alertmanager**: http://localhost:9093
- **Grafana**: http://localhost:3000
  - Username: `admin`
  - Password: `admin`
- **Loki**: http://localhost:3100
- **Jaeger UI**: http://localhost:16686

### 3. Verify Metrics

Actuator is on the management port (`9000`), not the public API port (`7777`) — see
issue #346. `docker-compose.dev.yml` publishes it on host loopback, so the commands
below work as written against the dev stack. In production the port is not published;
run them from inside the container instead:

```bash
docker compose -f docker-compose.prod.yml exec cashu-mint-rest \
  curl -s localhost:9000/actuator/prometheus | head -50
```


Check that metrics are being collected:

```bash
curl http://localhost:9000/actuator/prometheus | head -50
```

## Configuration

### Application Properties

The observability module is auto-configured when on the classpath. Default settings can be overridden in `application.properties`:

```properties
# Global toggle (default: true)
cashu.observability.enabled=true

# Task instrumentation - instruments all task classes with timing
cashu.observability.tasks.enabled=true

# Health indicators
cashu.observability.health.gateway.enabled=true
cashu.observability.health.gateway.timeout-ms=5000
cashu.observability.health.vault.enabled=true
cashu.observability.health.vault.timeout-ms=5000
```

### Prometheus Endpoint

Ensure the Prometheus endpoint is exposed:

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.prometheus.metrics.export.enabled=true
```

### Histogram Buckets

Request, task and lock timers publish percentile histograms in code, so
`_bucket` series exist by default and no configuration is required. This is
what makes the `histogram_quantile` panels and latency alerts work: a timer
without a histogram exports a Prometheus *summary*, which carries count, sum
and max but no buckets, so every quantile query returns "No data".
`ScrapeContractTest` fails the build if a timer family stops exporting buckets.

To narrow the buckets for better percentile resolution over a known latency
range, add explicit boundaries:

```properties
# Request latency buckets (seconds)
management.metrics.distribution.slo.cashu_mint_requests_duration_seconds=0.01,0.05,0.1,0.25,0.5,1.0,2.5,5.0,10.0

# Task duration buckets (seconds)
management.metrics.distribution.slo.cashu_mint_task_duration_seconds=0.001,0.005,0.01,0.025,0.05,0.1,0.25,0.5,1.0
```

## Grafana Dashboards

Pre-built dashboards are automatically provisioned:

### Cashu Mint Overview

High-level service health and key metrics:
- Service status (UP/DOWN)
- Request rate and error rate
- Request latency percentiles
- Task execution time and success/failure rate

### Cashu Mint Operations

Detailed operational metrics:
- Task execution rates and durations
- Task error rate by type
- HTTP request breakdown by endpoint

### Cashu Mint Integrity

Money-at-risk invariants and issuance/melt/webhook integrity. This is the
dashboard a critical page lands on:
- Payment sent but proofs not burned, melt stuck in `PAYMENT_UNKNOWN`, orphan
  voucher issuance (the DB-derived gauges of ADR 0002)
- Invariant poll failures, which tell you whether the three gauges above are
  fresh enough to trust
- Issuance and melt rejections by cause, webhook outcomes
- Voucher issuance and rejections by funding source and reason

### Voucher Liability, Token Integrity, IOU Liability

Financial reconciliation read from PostgreSQL through the read-only
`cashu_mint_grafana_ro` role rather than from Prometheus. These need
`CASHU_MINT_GRAFANA_RO_PASSWORD` to be set to the same value the mint used at
migration time; without it the datasource cannot authenticate and the panels
stay empty while the Prometheus dashboards work fine.

### Cashu Mint SLO/SLI

Service level objectives and indicators:
- Availability SLI (target: 99.9%)
- Latency SLI (P99 < 2s)
- Error budget tracking and burn rate
- Task success rates by type
- Historical SLO compliance timeline

### Cashu Mint Virtual Threads

Lock contention under virtual threads:
- Active locks
- Lock wait and hold time distributions

## Custom Prometheus Queries

### Request Metrics

```promql
# Request rate by endpoint
sum by (endpoint) (rate(cashu_mint_requests_total[5m]))

# P99 latency
histogram_quantile(0.99, sum by (le) (rate(cashu_mint_requests_duration_seconds_bucket[5m])))

# Error rate percentage
sum(rate(cashu_mint_requests_total{status=~"4xx|5xx"}[5m]))
  / sum(rate(cashu_mint_requests_total[5m])) * 100
```

### Voucher Metrics

```promql
# Vouchers issued per hour
increase(cashu_mint_voucher_issued_total[1h])

# Voucher rejections for missing funding
rate(cashu_mint_voucher_rejected_total{reason="funding_required"}[5m])
```

### Task Metrics

```promql
# Task success rate
sum by (task_name) (rate(cashu_mint_task_success_total[5m]))

# Task error rate by type
sum by (task_name, error_type) (rate(cashu_mint_task_failure_total[5m]))

# Slowest tasks (P95)
topk(5, histogram_quantile(0.95, sum by (task_name, le) (rate(cashu_mint_task_duration_seconds_bucket[5m]))))
```

## Health Endpoints

The observability module adds custom health indicators:

```bash
# Check overall health
curl http://localhost:9000/actuator/health

# Detailed health with indicators
curl http://localhost:9000/actuator/health | jq
```

Health indicators include:
- **gateway**: Lightning gateway connectivity
- **vault**: Database/vault connectivity

These surface on `/actuator/health` only — neither is exported as a Prometheus
series, so there is no alert on gateway or vault loss. Probe `/actuator/health`
externally (e.g. blackbox_exporter) if you need paging on it.

## Production Considerations

### Cardinality Management

To prevent metric explosion in production:

1. **Use bounded label values**: The module automatically normalizes endpoints to prevent high cardinality.

### Prometheus Configuration

Scrape targets come from file service discovery, not from `prometheus.yml`
itself, so the same config works in every environment. Point Prometheus at a
different mint by supplying your own targets file:

```yaml
# cashu-mint-observability/docker/prometheus/targets/cashu-mint.yml
- targets:
    - 'your-mint-host:9000'
  labels:
    env: 'staging'
    service: 'cashu-mint'
```

Mount an environment-specific directory instead of editing the default:

```bash
CASHU_PROMETHEUS_TARGETS_DIR=./prometheus/targets.staging \
  docker compose -f cashu-mint-observability/docker/docker-compose.observability.yml up -d
```

Two things to get right, both of which have silently emptied dashboards before:

- **Use the management port (`9000`), not the API port (`7777`).** The actuator
  runs on its own port (issue #346); `/actuator/prometheus` does not exist on
  the API port, so scrapes 404 and every panel reads "No data".
- **Prometheus must reach the mint on that port.** In the dev stack the port is
  published to host loopback only, so Prometheus scrapes it as a sibling
  container on the shared `cashu` network. Elsewhere, make sure the management
  port is reachable from Prometheus but not from the public internet.

Confirm the target is actually being scraped before trusting a dashboard:

```bash
curl -s localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job, health, lastError}'
```

A missing `cashu-mint` entry means no target matched the glob. Note that this
also makes `up{job="cashu-mint"}` *absent* rather than `0`, so the
`CashuMintDown` alert stays quiet: an empty dashboard is the only symptom.

### Grafana Persistence

The Docker Compose uses named volumes for data persistence:
- `cashu-prometheus-data` - Prometheus time series data
- `cashu-grafana-data` - Grafana configuration and dashboards
- `cashu-alertmanager-data` - Alertmanager state
- `cashu-loki-data` - Loki log data

## Alerting

### Prometheus Alerting Rules

Pre-configured alerts are defined in `docker/prometheus/alerts.yml`:

- **CashuMintDown**: Service is unreachable
- **CashuMintHighLatency**: P95 latency > 1 second
- **CashuMintHighErrorRate**: Error rate > 5%
- **CashuMintTaskFailureRate**: Task failure rate > 10%
- **CashuMintSlowTasks**: Task P95 latency > 500ms

Money-at-risk rules, which page rather than warn:

- **MeltPaymentSentBurnFailed**: payment settled, proofs still spendable
- **MeltStuckPaymentUnknown**: melt saga parked in `PAYMENT_UNKNOWN` past its TTL
- **VoucherOrphanIssuance**: voucher issued with no funding row
- **MintWebhookNotificationAbandoned**: the payment-adapter gave up forwarding a
  payment notification, so the payment settled but the mint never issued. The
  mint's own `cashu_mint_webhook_event_total` cannot detect this, because it
  counts deliveries that *arrived*; a notification never successfully sent is
  exactly the case it cannot see, so the signal comes from the adapter.

Each of these is paired with a rule that fires when the signal itself goes
missing (`InvariantGaugePollerStale`, `PaymentAdapterMetricsAbsent`), because a
gauge that is absent and a gauge reading zero look identical on a dashboard, and
only one of them means everything is fine.

### Testing alert rules

An alert nobody has seen fire is a hypothesis, not a safety net. The webhook
rules ship with promtool unit tests covering all three states that matter —
pages on a real loss, silent on a healthy adapter, and notices when it has been
disarmed by the adapter not being scraped:

```bash
docker run --rm --entrypoint promtool \
  -v "$PWD/cashu-mint-observability/docker/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v2.47.0 \
  test rules /etc/prometheus/tests/payment-adapter-webhook-alerts.yml
```

Validate the rule and scrape configuration the same way:

```bash
docker run --rm --entrypoint promtool \
  -v "$PWD/cashu-mint-observability/docker/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v2.47.0 check config /etc/prometheus/prometheus.yml
```

### Alertmanager Configuration

Edit `docker/alertmanager/alertmanager.yml` to configure notification channels:

```yaml
# Example: Slack integration
receivers:
  - name: 'critical-receiver'
    slack_configs:
      - channel: '#cashu-mint-alerts'
        api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
```

## Log Aggregation with Loki

Logs are automatically collected from Docker containers using Promtail and stored in Loki.

### Viewing Logs in Grafana

1. Open Grafana → Explore
2. Select "Loki" datasource
3. Use LogQL to query logs:

```logql
# All logs from cashu-mint
{container="cashu-mint-rest-dev"}

# Error logs only
{container="cashu-mint-rest-dev"} |= "ERROR"

# Logs with specific task
{container="cashu-mint-rest-dev"} |~ "MintTask|SwapTask"
```

### Log Correlation

Logs are automatically linked to traces via `trace_id` field when distributed tracing is enabled.

## Distributed Tracing

### Enable Tracing

Add to `application.properties`:

```properties
# Enable OpenTelemetry tracing
cashu.observability.tracing.enabled=true
cashu.observability.tracing.endpoint=http://jaeger:4317
cashu.observability.tracing.service-name=cashu-mint
cashu.observability.tracing.environment=development
cashu.observability.tracing.sampling-ratio=1.0
```

### Viewing Traces

1. Open Jaeger UI at http://localhost:16686
2. Select "cashu-mint" service
3. Search for traces by operation or tags

### Trace Context Propagation

Traces are automatically propagated using W3C Trace Context headers. When integrating with external services, ensure they support the `traceparent` header.

## Troubleshooting

### Metrics Not Appearing

1. Check the actuator endpoint is accessible:
   ```bash
   curl http://localhost:9000/actuator/prometheus
   ```

2. Verify Prometheus can reach the mint:
   ```bash
   docker logs cashu-prometheus 2>&1 | grep -i error
   ```

3. Check the network connectivity:
   ```bash
   docker network inspect cashu
   ```

### Grafana Shows No Data

1. Verify the datasource is configured:
   - Go to Grafana → Configuration → Data Sources
   - Test the Prometheus connection

2. Check the time range in Grafana (default: last 1 hour)

3. Ensure metrics are being scraped:
   - Go to Prometheus → Status → Targets
   - Verify the cashu-mint target is UP

### Health Indicator Shows DOWN

The health indicators track external service connectivity. If showing DOWN:

1. **Gateway**: Check Lightning gateway is reachable
2. **Vault**: Check database connection

Health status is updated based on recent operations - it may show UNKNOWN if no operations have occurred recently.

## Additional Resources

- [Metrics Reference](../../cashu-mint-observability/docs/metrics-reference.md) - Complete metrics documentation
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
