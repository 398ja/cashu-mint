# Virtual Thread Adoption

This document explains why cashu-mint adopted Java 21 virtual threads, summarizes the compatibility audit and pilot results, and describes the current state of the migration.

## Motivation

cashu-mint is I/O-heavy: every mint, melt, and swap request involves calls to the vault service (REST/HTTP), payment gateways (Lightning nodes via HTTP), and optionally Nostr relays (WebSocket). Under platform threads, each concurrent request consumes a ~1 MB OS thread stack, capping practical concurrency at a few hundred simultaneous connections.

Virtual threads (Project Loom) decouple concurrency from OS thread allocation. A virtual thread uses only a few KB when blocked on I/O, and the JVM multiplexes thousands of virtual threads onto a small pool of carrier (platform) threads. This makes cashu-mint's I/O-bound workload a natural fit.

## Library Compatibility Audit

Before enabling virtual threads, all dependencies were audited for compatibility. The key concern is **virtual thread pinning**: when a `synchronized` block contains blocking I/O, the virtual thread pins its carrier thread, defeating concurrency gains.

### Audit Results

| Category | Finding |
|----------|---------|
| Spring Boot 3.5.x | Official VT support since 3.2. Uses `ReentrantLock` internally. |
| HikariCP 5.x | VT-compatible connection pool. |
| PostgreSQL JDBC 42.x | VT-compatible driver. |
| Jackson 2.x | JSON parsing is CPU-bound; no pinning risk. |
| cashu-lib 0.12.0 | Zero `synchronized` blocks, zero `ThreadLocal`, zero blocking I/O across 110+ source files. Includes a `VirtualThreadConcurrencyTest` suite. |
| Bouncy Castle | CPU-bound cryptographic operations only. |
| payment-adapter-phoenixd | Uses Spring `RestTemplate` (VT-compatible). Needs explicit timeouts. |
| cashu-voucher-nostr | WebSocket connections need further review for VT compatibility. |

**Conclusion:** No critical blockers. All major dependencies use VT-compatible patterns.

For the full dependency-by-dependency audit, see [Archive: Library audit](../archive/loom/library-audit.md).

## Pilot Results

A load test pilot compared platform threads against virtual threads under identical conditions (100 concurrent users, 4-minute duration, k6 load generator).

### Key Metrics

| Metric | Platform Threads | Virtual Threads | Change |
|--------|-----------------|-----------------|--------|
| Throughput (req/s) | 160.00 | 160.14 | +0.09% |
| p95 Latency (ms) | 15.00 | 15.97 | +6.5% |
| p99 Latency (ms) | 27.06 | 29.41 | +8.7% |
| Error Rate (%) | 20.22 | 19.77 | -2.2% |
| VT Pinning Events | N/A | 0 | N/A |

Throughput was maintained, latency increase was within the 10% tolerance threshold, and zero pinning events were detected. The error rate (keysets endpoint returning 500) was unrelated to virtual threads.

**Decision: GO** — proceed with virtual thread adoption.

For the detailed pilot protocol and raw data, see [Archive: Pilot results](../archive/loom/pilot-results.md) and [Archive: Phase 1 pilot guide](../archive/loom/phase1-pilot-guide.md).

## Pool Tuning

With virtual threads enabled, Tomcat no longer provides backpressure through its thread pool. Explicit connection limits were configured to prevent resource exhaustion:

| Setting | Value | Rationale |
|---------|-------|-----------|
| `max-connections` | 2000 | Hard limit on concurrent connections |
| `accept-count` | 100 | Queue for overflow; excess rejected with 503 |
| `threads.max` | 50 | Carrier thread pool (reduced from default 200) |
| `connection-timeout` | 20000ms | Prevent slow client attacks |

Load testing confirmed these settings provide 20x headroom over the tested 100-VU load, with carrier thread utilization peaking at ~25 of the 50 available.

For full tuning data, see [Archive: Pool tuning results](../archive/loom/pool-tuning-results.md).

## Current Status

Virtual threads are enabled by default (`spring.threads.virtual.enabled=true`) and used across:

| Component | Usage |
|-----------|-------|
| Tomcat request handling | VT per HTTP request |
| `@Async` tasks | VT via `AsyncConfig` executor |
| Gateway HTTP calls | VT executor in `GatewayClientConfiguration` |
| WebSocket notifications | Parallel VT fan-out to subscribers |
| Lock managers | `ReentrantLock` (VT-safe, avoids pinning) |

### Remaining Work

- Run a 24-hour soak test to verify memory stability under sustained load.
- Full overload test at 3000 concurrent users.
- Audit `cashu-voucher-nostr` WebSocket client for VT compatibility.
- Capture JFR recordings for detailed blocking analysis.

## See Also

- [Virtual thread issues runbook](../runbooks/virtual-thread-issues.md)
- [Archive: Library audit](../archive/loom/library-audit.md)
- [Archive: Pilot results](../archive/loom/pilot-results.md)
- [Archive: Pool tuning results](../archive/loom/pool-tuning-results.md)
