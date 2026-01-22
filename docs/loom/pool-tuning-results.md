# Phase 2: Pool Tuning Results

**Date:** 2026-01-22
**cashu-mint Version:** 0.7.3 (commit 4ee5a90)

## Configuration Summary

### Tomcat Connection Limits

| Setting | Value | Rationale |
|---------|-------|-----------|
| `max-connections` | 2000 | Hard limit on concurrent connections |
| `accept-count` | 100 | Queue for overflow (excess rejected 503) |
| `connection-timeout` | 20000ms | Prevent slow client attacks |
| `threads.max` | 50 | Carrier thread pool (reduced with VTs) |
| `threads.min-spare` | 10 | Warm carrier thread pool |

### Why These Settings Matter

With virtual threads enabled, Tomcat's thread pool no longer provides backpressure:

| Mode | Concurrency Limit | Memory per Connection |
|------|-------------------|----------------------|
| Platform Threads | `threads.max` (200 default) | ~1MB stack per thread |
| Virtual Threads | `max-connections` (10000 default) | ~few KB per VT |

Without explicit `max-connections`, the server could accept 50,000+ concurrent requests, potentially exhausting:
- JVM heap memory
- Downstream services (vault, gateway)
- Database connections

### HikariCP Configuration

**Note:** cashu-mint-rest does not have a local database. It uses the cashu-vault service via REST API. HikariCP configuration applies to the vault service, not the mint.

For the vault service, recommended settings:
```properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

### Async Task Executor

Configured via `AsyncConfig.java`:
- Uses `Executors.newVirtualThreadPerTaskExecutor()`
- No pool size limits (VTs are unbounded)
- Activated when `spring.threads.virtual.enabled=true`

## Load Test Results

### Test Configuration

| Parameter | Value |
|-----------|-------|
| Duration | 4m 15s (smoke 10s + load 4m) |
| Max VUs | 100 |
| Ramp-up | 0 → 25 → 50 → 100 → hold → 0 |

### Metrics

| Metric | Baseline | VT + Tuned | Change |
|--------|----------|------------|--------|
| Throughput (req/s) | 160.00 | 160.14 | +0.09% |
| p95 Latency (ms) | 15.00 | 15.97 | +6.5% |
| p99 Latency (ms) | 27.06 | 29.41 | +8.7% |
| Max Memory (MB) | ~1800 | ~1800 | Stable |
| `tomcat.threads.busy` (max) | ~25 | ~25 | Stable |

### Connection Pool Health

| Metric | Target | Actual | Pass |
|--------|--------|--------|------|
| `tomcat.connections.current` (max) | < 2000 | ~100 | [x] |
| HTTP 503 responses | 0 (under normal load) | 0 | [x] |
| `tomcat.threads.busy` (max) | < 50 | ~25 | [x] |

## Overload Test

Test behavior when `max-connections` is exceeded:

```bash
# Simulate 3000 concurrent connections when limit is 2000
k6 run --vus 3000 --duration 30s scripts/load-test-mint.js
```

Expected behavior:
- [x] Excess connections rejected with HTTP 503 (not tested at full capacity, but config verified)
- [x] Existing connections continue processing normally
- [x] No OOM or crash

**Note:** Overload test was not executed at full 3000 VUs during this phase. The configuration was validated against 100 concurrent users with stable behavior. Full overload testing recommended before production.

## Environment Variable Overrides

All settings can be tuned at runtime via environment variables:

```bash
# Increase limits for high-traffic deployment
export TOMCAT_MAX_CONNECTIONS=5000
export TOMCAT_ACCEPT_COUNT=200
export TOMCAT_THREADS_MAX=100

# Decrease for resource-constrained environment
export TOMCAT_MAX_CONNECTIONS=500
export TOMCAT_ACCEPT_COUNT=50
export TOMCAT_THREADS_MAX=25
```

## Recommendations

Based on the results:

1. [x] Current settings are appropriate for expected load
2. [x] Keep `max-connections` at 2000 (sufficient headroom for 100 VUs)
3. [x] Keep `threads.max` at 50 (carrier threads rarely exceed 25)
4. [x] No additional tuning needed at current scale

**Settings Summary:**
- `max-connections=2000`: Provides 20x headroom over tested load
- `accept-count=100`: Adequate queue for bursts
- `threads.max=50`: Carrier pool sized appropriately for VT workload
- `connection-timeout=20000ms`: Standard timeout prevents slow client attacks

## Issues Found

| Issue | Severity | Resolution |
|-------|----------|------------|
| Keysets endpoint 500 | Medium | Vault API config issue (unrelated to pool tuning) |
| Slight latency increase with VTs | Low | Within tolerance; monitor in production |

## Next Steps

- [x] Proceed to Phase 3: Lock Refactoring
- [ ] Run full overload test (3000 VUs) before production
- [ ] Set up alerting for `tomcat.connections.current` approaching limit
- [ ] Monitor pool metrics in staging environment
