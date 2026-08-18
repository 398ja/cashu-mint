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

Configure latency histogram buckets for better percentile accuracy:

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

### Cashu Mint Business

Business metrics:
- Vouchers issued
- Voucher issuance rate

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
rate(cashu_mint_voucher_funding_required_total[5m])
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

For production, update `prometheus.yml` with your actual mint hostname:

```yaml
scrape_configs:
  - job_name: 'cashu-mint'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['your-mint-host:7777']
```

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
