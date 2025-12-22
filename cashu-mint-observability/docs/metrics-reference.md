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

## Mint Operations Metrics

Core metrics for proof and signature operations.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_proofs_issued_total` | Counter | `unit` | Total proofs issued |
| `cashu_mint_proofs_spent_total` | Counter | `unit` | Total proofs spent |
| `cashu_mint_proofs_issued_keyset_total` | Counter | `keyset_id`, `unit` | Proofs issued per keyset |
| `cashu_mint_proofs_spent_keyset_total` | Counter | `keyset_id`, `unit` | Proofs spent per keyset |
| `cashu_mint_proofs_double_spend_total` | Counter | - | Double-spend attempts (global) |
| `cashu_mint_proofs_double_spend_keyset_total` | Counter | `keyset_id` | Double-spend attempts per keyset |
| `cashu_mint_signatures_generated_total` | Counter | - | Blind signatures created |
| `cashu_mint_signatures_verified_total` | Counter | - | Signatures verified |
| `cashu_mint_sats_issued_total` | Counter | `unit` | Total sats issued |
| `cashu_mint_sats_redeemed_total` | Counter | `unit` | Total sats redeemed |
| `cashu_mint_sats_outstanding` | Gauge | `unit` | Current liability (issued - redeemed) |
| `cashu_mint_keysets_active` | Gauge | `unit` | Number of active keysets |

### Example Queries

```promql
# Outstanding liability
cashu_mint_sats_outstanding

# Proof issuance rate
rate(cashu_mint_proofs_issued_total[5m])

# Double-spend detection rate
rate(cashu_mint_proofs_double_spend_total[5m])
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

## Quote Metrics

Metrics for mint and melt quote operations.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_quotes_created_total` | Counter | `type`, `method` | Quotes created |
| `cashu_mint_quotes_completed_total` | Counter | `type`, `method` | Quotes completed |
| `cashu_mint_quotes_expired_total` | Counter | `type`, `method` | Quotes expired |
| `cashu_mint_quotes_failed_total` | Counter | `type`, `method`, `reason` | Quotes failed |
| `cashu_mint_quotes_active` | Gauge | `type` | Active quote count |
| `cashu_mint_quotes_amount_total` | Counter | `type`, `unit` | Total amount requested |
| `cashu_mint_quotes_completed_amount_total` | Counter | `type`, `unit` | Total amount completed |
| `cashu_mint_quotes_processing_duration_seconds` | Timer | `type`, `method` | Quote processing time |

### Labels

- `type`: Quote type (`mint` or `melt`)
- `method`: Payment method (`bolt11`, `onchain`, etc.)
- `reason`: Failure reason (e.g., `payment_timeout`, `insufficient_funds`)

### Example Queries

```promql
# Quote completion rate
sum by (type) (rate(cashu_mint_quotes_completed_total[5m]))
  / sum by (type) (rate(cashu_mint_quotes_created_total[5m]))

# Active quotes by type
cashu_mint_quotes_active

# Quote failure reasons
sum by (type, reason) (rate(cashu_mint_quotes_failed_total[5m]))
```

---

## Voucher Metrics

Metrics for voucher (gift card) operations.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_vouchers_issued_total` | Counter | - | Vouchers issued |
| `cashu_mint_vouchers_redeemed_total` | Counter | - | Vouchers redeemed |
| `cashu_mint_vouchers_rejected_total` | Counter | `reason` | Vouchers rejected |
| `cashu_mint_vouchers_quotes_active` | Gauge | - | Active voucher quotes |
| `cashu_mint_vouchers_face_value_issued_total` | Counter | `unit` | Face value issued |
| `cashu_mint_vouchers_face_value_redeemed_total` | Counter | `unit` | Face value redeemed |
| `cashu_mint_vouchers_fees_collected_total` | Counter | `unit` | Fees collected |
| `cashu_mint_vouchers_quote_duration_seconds` | Timer | - | Quote processing time |
| `cashu_mint_vouchers_redemption_duration_seconds` | Timer | - | Redemption time |

### Rejection Reasons

- `expired` - Voucher has expired
- `invalid_signature` - Invalid cryptographic signature
- `already_redeemed` - Voucher already used
- `quote_expired` - Quote expired before completion

### Example Queries

```promql
# Voucher issuance rate
rate(cashu_mint_vouchers_issued_total[5m])

# Voucher rejection breakdown
sum by (reason) (rate(cashu_mint_vouchers_rejected_total[5m]))

# Total fees collected
cashu_mint_vouchers_fees_collected_total
```

---

## Gateway Metrics

Metrics for Lightning gateway operations.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `cashu_mint_gateway_payments_sent_total` | Counter | `gateway` | Total payments sent |
| `cashu_mint_gateway_payments_received_total` | Counter | `gateway` | Total payments received |
| `cashu_mint_gateway_payment_failures_total` | Counter | `gateway`, `error_type` | Payment failures |
| `cashu_mint_gateway_amount_sent_total` | Counter | `gateway`, `unit` | Total amount sent (sats) |
| `cashu_mint_gateway_amount_received_total` | Counter | `gateway`, `unit` | Total amount received (sats) |
| `cashu_mint_gateway_routing_fees_total` | Counter | `gateway`, `unit` | Routing fees paid (sats) |
| `cashu_mint_gateway_invoices_created_total` | Counter | - | Total invoices created |
| `cashu_mint_gateway_invoices_paid_total` | Counter | - | Total invoices paid |
| `cashu_mint_gateway_invoices_expired_total` | Counter | - | Total invoices expired |
| `cashu_mint_gateway_pending_payments` | Gauge | - | Number of pending payments |
| `cashu_mint_gateway_health` | Gauge | - | Gateway health (1=healthy, 0=unhealthy) |
| `cashu_mint_gateway_payment_send_duration_seconds` | Timer | `gateway` | Time to send payment |
| `cashu_mint_gateway_payment_receive_duration_seconds` | Timer | `gateway` | Time to receive payment |
| `cashu_mint_gateway_invoice_creation_duration_seconds` | Timer | `gateway` | Time to create invoice |

### Labels

- `gateway`: Gateway type (e.g., `bolt11`, `phoenixd`)
- `error_type`: Error type (e.g., `timeout`, `no_route`, `insufficient_funds`)

### Example Queries

```promql
# Total routing fees by gateway
sum by (gateway) (cashu_mint_gateway_routing_fees_total)

# Average routing fee per payment
rate(cashu_mint_gateway_routing_fees_total[5m]) / rate(cashu_mint_gateway_payments_sent_total[5m])

# Payment failure rate
sum(rate(cashu_mint_gateway_payment_failures_total[5m]))
  / (sum(rate(cashu_mint_gateway_payments_sent_total[5m]))
     + sum(rate(cashu_mint_gateway_payment_failures_total[5m])))

# Invoice conversion rate
cashu_mint_gateway_invoices_paid_total / cashu_mint_gateway_invoices_created_total
```

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

# Voucher metrics
cashu.observability.vouchers.enabled=true

# Health indicators
cashu.observability.health.gateway.enabled=true
cashu.observability.health.vault.enabled=true
```

### Prometheus Endpoint

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.prometheus.enabled=true
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

Five pre-built dashboards are provided:

### Cashu Mint Overview
- Service health status
- Outstanding liability
- Request rate and latency
- Error rate overview

### Cashu Mint Operations
- Task execution metrics
- Proof operations
- Signature operations
- HTTP request details

### Cashu Mint Business
- Financial overview (issued/redeemed/outstanding)
- Quote operations
- Voucher metrics
- Fee collection

### Cashu Mint SLO/SLI
- Availability SLI (target: 99.9%)
- Latency SLI (P99 target: < 2s)
- Error budget tracking and burn rate
- Task success rates by type
- Historical SLO compliance

### Cashu Mint Cost Analysis
- Outstanding liability
- Routing fees paid vs fees collected
- Net profit/loss tracking
- Gateway cost breakdown
- Volume analysis
- Invoice conversion rate

---

## Cardinality Considerations

To prevent metric cardinality explosion:

1. **Endpoint normalization**: Variable path segments are replaced with placeholders
   - `/v1/keys/abc123` → `/v1/keys/{keyset_id}`
   - `/v1/mint/quote/bolt11/uuid` → `/v1/mint/quote/{method}/{quote_id}`

2. **Keyset tracking**: Can be disabled with `cashu.observability.metrics.trackKeysets=false`

3. **Limited label values**: Error types use exception class names (bounded set)

4. **No high-cardinality data**: Quote IDs, proof secrets, and user data are never included in labels
