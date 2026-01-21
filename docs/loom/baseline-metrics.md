# Baseline Performance Metrics

This document captures baseline performance metrics for cashu-mint **before** enabling virtual threads.

## Test Environment

| Parameter | Value |
|-----------|-------|
| **Date** | YYYY-MM-DD |
| **Commit** | (git SHA) |
| **Hardware** | |
| CPU | (e.g., 4 vCPU) |
| RAM | (e.g., 8 GB) |
| Storage | (e.g., SSD) |
| **Software** | |
| JDK Version | 21.x |
| Spring Boot | 3.5.6 |
| PostgreSQL | (version) |
| **Configuration** | |
| HikariCP pool size | 10 |
| Tomcat max-threads | 200 |
| Virtual threads | **disabled** |
| **Gateway Mode** | |
| Gateway type | Dummy (stub) |
| Simulated latency | 50ms |

## Load Test Parameters

```bash
# k6 command used
k6 run --out json=baseline-results.json scripts/load-test-mint.js
```

## Results Summary

### Throughput (requests/second)

| VUs | /info | /keysets | /mint/quote | /melt/quote | Overall |
|-----|-------|----------|-------------|-------------|---------|
| 25  | TBD   | TBD      | TBD         | TBD         | TBD     |
| 50  | TBD   | TBD      | TBD         | TBD         | TBD     |
| 100 | TBD   | TBD      | TBD         | TBD         | TBD     |

### Latency (milliseconds)

| Metric | /info | /keysets | /mint/quote | /melt/quote |
|--------|-------|----------|-------------|-------------|
| p50    | TBD   | TBD      | TBD         | TBD         |
| p95    | TBD   | TBD      | TBD         | TBD         |
| p99    | TBD   | TBD      | TBD         | TBD         |

### Resource Utilization

| Metric | 25 VUs | 50 VUs | 100 VUs |
|--------|--------|--------|---------|
| Tomcat threads.busy (max) | TBD | TBD | TBD |
| HikariCP connections.active (max) | TBD | TBD | TBD |
| HikariCP connections.pending (max) | TBD | TBD | TBD |
| Heap memory used (max) | TBD | TBD | TBD |

### Error Rates

| VUs | Error Rate | Notes |
|-----|------------|-------|
| 25  | TBD        |       |
| 50  | TBD        |       |
| 100 | TBD        |       |

## JFR Analysis

JFR recording file: `baseline-YYYYMMDD-HHMMSS.jfr`

### Blocking Hotspots

| Location | Total Blocked Time | Count |
|----------|-------------------|-------|
| TBD | TBD | TBD |

### Lock Contention

| Lock | Contention Events | Avg Wait (ms) |
|------|-------------------|---------------|
| MINT_MELT_LOCK | TBD | TBD |

## Artifacts

- [ ] `results/baseline-YYYYMMDD-HHMMSS.json` - k6 results
- [ ] `results/baseline-YYYYMMDD-HHMMSS.jfr` - JFR recording
- [ ] `results/baseline-YYYYMMDD-HHMMSS-env.txt` - Environment profile
- [ ] `results/baseline-YYYYMMDD-HHMMSS-metrics.json` - Prometheus snapshot

## Commands Used

```bash
# Start application with JFR recording
java -XX:StartFlightRecording=filename=baseline.jfr,duration=5m,settings=profile \
     -jar cashu-mint-rest/target/cashu-mint-rest-*-exec.jar

# Run load test (25 VUs)
k6 run --vus 25 --duration 60s --out json=results/baseline-25vu.json scripts/load-test-mint.js

# Run load test (50 VUs)
k6 run --vus 50 --duration 60s --out json=results/baseline-50vu.json scripts/load-test-mint.js

# Run load test (100 VUs)
k6 run --vus 100 --duration 60s --out json=results/baseline-100vu.json scripts/load-test-mint.js

# Export Prometheus metrics
curl -s http://localhost:7777/actuator/prometheus > results/baseline-metrics.txt
```

## Notes

(Add any observations, anomalies, or issues encountered during baseline testing)
