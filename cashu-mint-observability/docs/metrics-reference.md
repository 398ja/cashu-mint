# Cashu Mint Metrics Reference

This document provides a comprehensive reference for all metrics exposed by the `cashu-mint-observability` module.

## Overview

All metrics use the prefix `cashu_mint_` and follow Prometheus naming conventions. Metrics are exposed via the `/actuator/prometheus` endpoint.

## Metric Types

- **Counter**: Monotonically increasing value (resets on restart)
- **Gauge**: Value that can go up or down
- **Timer**: Measures duration with histogram buckets

---

## Request Metrics

HTTP request metrics following the RED (Rate, Errors, Duration) pattern.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_requests_total` | Counter | `endpoint`, `method`, `status` | Total HTTP requests |
| `cashu_mint_requests_duration_seconds` | Timer | `endpoint`, `method` | Request latency histogram |
| `cashu_mint_requests_errors_total` | Counter | `endpoint`, `error_type` | Failed requests |

### Labels

- `endpoint`: Normalized endpoint path (e.g., `/v1/swap`, `/v1/mint/quote/{method}/{quote_id}`)
- `method`: HTTP method (GET, POST, etc.)
- `status`: Status group (2xx, 3xx, 4xx, 5xx)
- `error_type`: Exception class name or `http_{status_code}`

### Example Queries

```promql
# Request rate by endpoint
sum by (endpoint) (rate(cashu_mint_requests_total[5m]))

# P99 latency
histogram_quantile(0.99, sum by (endpoint, le) (rate(cashu_mint_requests_duration_seconds_bucket[5m])))

# Error rate
sum(rate(cashu_mint_requests_errors_total[5m])) / sum(rate(cashu_mint_requests_total[5m]))
```

---

## Task Metrics

Metrics for protocol task execution (SwapTask, MintTask, MeltTask, etc.).

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_task_duration_seconds` | Timer | `task_name` | Task execution time |
| `cashu_mint_task_success_total` | Counter | `task_name` | Successful task executions |
| `cashu_mint_task_failure_total` | Counter | `task_name`, `error_type` | Failed task executions |

### Task Names

- `SwapTask`
- `MintTask`
- `MintTokensTask`
- `MeltTask`
- `MeltTokensTask`
- `SignBlindedMessageTask`
- `VerifyProofsTask`
- `VerifyFeesTask`
- `CheckStateTask`
- `RestoreSignaturesTask`
- `MintQuoteTask`
- `MeltQuoteTask`
- `VoucherMintQuoteTask`

### Example Queries

```promql
# Task success rate
sum by (task_name) (rate(cashu_mint_task_success_total[5m]))

# Task failure rate by error type
sum by (task_name, error_type) (rate(cashu_mint_task_failure_total[5m]))

# P95 task duration
histogram_quantile(0.95, sum by (task_name, le) (rate(cashu_mint_task_duration_seconds_bucket[5m])))

# Task error rate percentage
sum by (task_name) (rate(cashu_mint_task_failure_total[5m]))
  / (sum by (task_name) (rate(cashu_mint_task_success_total[5m]))
     + sum by (task_name) (rate(cashu_mint_task_failure_total[5m])))
```

---

## Lock Metrics

Contention metrics for the per-quote and per-proof locks that guard against
double-mint / double-spend races. Emitted through the `LockMetricsRecorder`
port in `cashu-mint-protocol`.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_lock_wait_seconds` | Timer | `lock_type` | Time spent waiting to acquire a lock |
| `cashu_mint_lock_hold_seconds` | Timer | `lock_type` | Time a lock was held |
| `cashu_mint_lock_active` | Gauge | `lock_type` | Locks currently held |

---

## Invariant Metrics

Operational invariants are exported as gauges re-derived from the database by
`cashu-mint-jpa`'s `InvariantGaugePoller` every 60 seconds, not as counters
incremented on a state transition — see
[ADR 0002](../../docs/adr/0002-db-derived-gauges-for-operational-invariants.md).
The poller assumes a single mint instance.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_melt_stuck_payment_unknown` | Gauge | — | Melt sagas in `PAYMENT_UNKNOWN` for longer than `cashu.mint.melt.payment-unknown-ttl` |
| `cashu_mint_invariant_poll_failures_total` | Counter | — | Invariant polls that threw; a non-zero rate means the gauges are stale |

The SQL behind each gauge is documented on the repository that owns the table
(`MeltSagaJpaRepository`), and is repeated in the alert's `query` annotation so
the first diagnostic step ships with the page.

Alerts:

- `MeltStuckPaymentUnknown` (`critical`) — the age threshold lives in the SQL,
  bound from `cashu.mint.melt.payment-unknown-ttl`, so shortening the property
  shortens time to detection. The rule's `for: 5m` is only a scrape-debounce.
- `InvariantGaugePollerStale` (`warning`) — the gauges fail open, so this rule
  watches for the series going absent or the poll-failure counter rising;
  without it a broken poll would leave a stale zero and disarm the page.

---

## Health Indicators

Custom health indicators exposed via `/actuator/health`.

### Gateway Health

Tracks Lightning gateway connectivity.

**Status Details:**
- `status`: `connected`, `unhealthy`, `no_recent_success`, `stale`
- `lastSuccessMs`: Time since last successful operation
- `successCount`: Total successful operations
- `errorCount`: Total failed operations
- `lastError`: Most recent error message

### Vault Health

Tracks vault/database connectivity.

**Status Details:**
- `status`: `connected`, `unhealthy`, `no_recent_success`, `stale`
- `connection`: `connected`, `disconnected`, `initialized`
- `lastSuccessMs`: Time since last successful operation
- `successCount`: Total successful operations
- `errorCount`: Total failed operations
- `lastError`: Most recent error message

---

## Configuration

### Enabling/Disabling Metrics

```properties
# Global toggle
cashu.observability.enabled=true

# Task instrumentation
cashu.observability.tasks.enabled=true

# Health indicators
cashu.observability.health.gateway.enabled=true
cashu.observability.health.vault.enabled=true
```

### Prometheus Endpoint

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.prometheus.metrics.export.enabled=true
```

### Histogram Buckets

```properties
# Request latency buckets (seconds)
management.metrics.distribution.slo.cashu_mint_requests_duration_seconds=0.01,0.05,0.1,0.25,0.5,1.0,2.5,5.0,10.0

# Task duration buckets (seconds)
management.metrics.distribution.slo.cashu_mint_task_duration_seconds=0.001,0.005,0.01,0.025,0.05,0.1,0.25,0.5,1.0
```

---

## Grafana Dashboards

Pre-built dashboards are provided:

### Cashu Mint Overview
- Service health status
- Request rate and latency
- Error rate overview
- Task execution time and success/failure rate

### Cashu Mint Operations
- Task execution metrics
- Task error rate by type
- HTTP request details

### Cashu Mint Business
- Vouchers issued and issuance rate

### Cashu Mint SLO/SLI
- Availability SLI (target: 99.9%)
- Latency SLI (P99 target: < 2s)
- Error budget tracking and burn rate
- Task success rates by type
- Historical SLO compliance

### Cashu Mint Virtual Threads
- Active locks
- Lock wait and hold time distributions

---

## Cardinality Considerations

To prevent metric cardinality explosion:

1. **Endpoint normalization**: Variable path segments are replaced with placeholders
   - `/v1/keys/abc123` → `/v1/keys/{keyset_id}`
   - `/v1/mint/quote/bolt11/uuid` → `/v1/mint/quote/{method}/{quote_id}`

2. **Limited label values**: Error types use exception class names (bounded set)

3. **No high-cardinality data**: Quote IDs, proof secrets, and user data are never included in labels
