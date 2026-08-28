# Virtual Thread Issues Runbook

> **Other runbooks in this directory**:
> [voucher-data-minimisation.md](voucher-data-minimisation.md) — operator
> procedures for spec 004 (salt generation, identity backfill,
> retention purge, forensic CLI, salt rotation).

This runbook covers diagnosing and resolving issues related to Java 21 virtual threads in the Cashu Mint.

## Quick Reference

| Issue | Symptom | First Action |
|-------|---------|--------------|
| High latency | p99 > 500ms | Check lock wait times in Grafana |
| Thread starvation | Request timeouts | Check BLOCKED thread count |
| Memory growth | Heap increasing over time | Capture heap dump, check for VT leaks |
| Pinning | High CPU with low throughput | Run JFR with pinning detection |

## Environment Variables

```bash
# Disable virtual threads (emergency rollback)
SPRING_THREADS_VIRTUAL_ENABLED=false

# Adjust Tomcat limits
TOMCAT_MAX_CONNECTIONS=2000
TOMCAT_THREADS_MAX=50

# Gateway timeouts
GATEWAY_CLIENT_CONNECT_TIMEOUT=5s
GATEWAY_CLIENT_READ_TIMEOUT=30s
```

---

## Issue 1: High Lock Contention

### Symptoms
- `cashu_mint_lock_wait_seconds` p99 > 100ms
- Increasing active lock count
- Degraded request latency

### Diagnosis

1. Check Grafana "Virtual Threads" dashboard:
   - Lock Wait Time panel
   - Active Locks panel

2. Identify contention source:
   ```bash
   # Check which lock types have high wait times
   curl -s localhost:9000/actuator/prometheus | grep cashu_mint_lock_wait
   ```

3. If `quote` locks show high contention:
   - Many concurrent requests for the same quote
   - Gateway payment checks are slow

4. If `proof` locks show high contention:
   - Many concurrent melt requests with overlapping proofs

### Resolution

**Immediate:**
- Scale horizontally if load is legitimate
- Check gateway health (slow payment verification causes long lock holds)

**Long-term:**
- Review quote lifecycle - are quotes being reused incorrectly?
- Consider increasing gateway timeouts if payment verification is slow

---

## Issue 2: Virtual Thread Pinning

### Symptoms
- High CPU utilization with low throughput
- JFR shows `jdk.VirtualThreadPinned` events
- Latency spikes without corresponding load increase

### Diagnosis

1. Enable JFR recording with pinning detection:
   ```bash
   java -XX:StartFlightRecording=filename=recording.jfr,settings=profile \
        -jar cashu-mint-rest.jar
   ```

2. Analyze with JDK Mission Control:
   ```bash
   jmc recording.jfr
   ```
   Look for `jdk.VirtualThreadPinned` events.

3. Alternative: Use `-Djdk.tracePinnedThreads=short` for immediate console output:
   ```bash
   java -Djdk.tracePinnedThreads=short -jar cashu-mint-rest.jar
   ```

### Common Pinning Causes

| Cause | Stack Trace Indicator | Resolution |
|-------|----------------------|------------|
| Synchronized block | `synchronized` in stack | Replace with `ReentrantLock` |
| Native method | JNI frame in stack | Often unavoidable, optimize usage |
| Third-party library | External package in stack | Update library or file issue |

### Resolution

**Immediate:**
- Increase carrier thread pool: `-Djdk.virtualThreadScheduler.parallelism=<N>`
- Default is `Runtime.getRuntime().availableProcessors()`

**Long-term:**
- Identify and replace synchronized blocks with `ReentrantLock`
- Update or replace pinning libraries

---

## Issue 3: Memory Leak / Heap Growth

### Symptoms
- Heap usage grows continuously over 24+ hours
- GC pause times increasing
- Eventually OOM errors

### Diagnosis

1. Capture heap dump:
   ```bash
   jcmd <pid> GC.heap_dump /tmp/heap.hprof
   ```

2. Analyze with Eclipse MAT or VisualVM:
   - Look for large collections of `Continuation` objects
   - Check for unreleased lock references

3. Monitor heap trend:
   ```bash
   # Watch heap usage over time
   watch -n 5 'curl -s localhost:9000/actuator/metrics/jvm.memory.used | jq .'
   ```

### Common Causes

| Cause | Indicator | Resolution |
|-------|-----------|------------|
| Lock leak | Growing `QuoteLockManager.LOCKS` | Check lock release in finally blocks |
| Unclosed resources | Growing connection objects | Audit try-with-resources usage |
| Stuck VTs | Continuations not completing | Check for blocked I/O |

### Resolution

**Immediate:**
- Restart affected instances
- Disable VTs if memory growth is severe: `SPRING_THREADS_VIRTUAL_ENABLED=false`

**Long-term:**
- Add memory alerting at 80% heap threshold
- Review resource cleanup patterns

---

## Issue 4: Gateway Timeout Cascade

### Symptoms
- Gateway calls timing out
- Lock hold times increasing (waiting for gateway)
- Cascading failures

### Diagnosis

1. Check gateway health:
   ```bash
   curl -s localhost:9000/actuator/health | jq '.components.gateway'
   ```

2. Review gateway metrics:
   ```bash
   curl -s localhost:9000/actuator/prometheus | grep gateway
   ```

3. Check lock hold times - long holds indicate slow gateway:
   ```promql
   histogram_quantile(0.99, rate(cashu_mint_lock_hold_seconds_bucket[5m]))
   ```

### Resolution

**Immediate:**
- Check gateway service health
- Reduce gateway timeouts to fail fast:
  ```bash
  GATEWAY_CLIENT_CONNECT_TIMEOUT=2s
  GATEWAY_CLIENT_READ_TIMEOUT=10s
  ```

**Long-term:**
- Implement circuit breaker for gateway calls
- Add gateway-specific health checks

---

## Rollback Procedure

If virtual threads are causing issues in production:

### Step 1: Disable VTs
```bash
# Set environment variable
export SPRING_THREADS_VIRTUAL_ENABLED=false

# Restart application
systemctl restart cashu-mint
# or
kubectl rollout restart deployment/cashu-mint
```

### Step 2: Verify Rollback
```bash
# Check that VTs are disabled
curl -s localhost:9000/actuator/env | grep virtual
# Should show: spring.threads.virtual.enabled=false

# Check thread pool behavior
curl -s localhost:9000/actuator/prometheus | grep tomcat_threads
# Busy threads should increase under load (platform thread behavior)
```

### Step 3: Monitor
- Verify latency returns to baseline
- Check error rates stabilize
- Monitor for any regression in throughput

---

## Diagnostic Commands

### Thread Dump
```bash
jcmd <pid> Thread.print > thread_dump.txt
```

### JFR Recording
```bash
# Start recording
jcmd <pid> JFR.start name=diagnosis duration=60s filename=/tmp/diag.jfr

# Stop recording
jcmd <pid> JFR.stop name=diagnosis
```

### Heap Dump
```bash
jcmd <pid> GC.heap_dump /tmp/heap.hprof
```

### Check VT Configuration
```bash
# Check if VTs are enabled
curl -s localhost:9000/actuator/env/spring.threads.virtual.enabled
```

### Prometheus Queries

```promql
# Lock contention
histogram_quantile(0.99, rate(cashu_mint_lock_wait_seconds_bucket[5m]))

# Active locks
cashu_mint_lock_active

# Thread states (look for BLOCKED)
jvm_threads_states_threads{state="blocked"}

# Tomcat connection usage
tomcat_connections_current_connections / tomcat_connections_config_max_connections
```

---

## Escalation Path

1. **L1 - On-Call Engineer**
   - Follow this runbook
   - Attempt rollback if issues persist > 15 minutes

2. **L2 - Platform Team**
   - Deep JFR analysis
   - Code-level investigation
   - Contact: #platform-oncall

3. **L3 - Core Engineering**
   - JVM-level issues
   - Upstream bug reports
   - Contact: #core-eng

---

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Spring Boot Virtual Threads](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.spring-application.virtual-threads)
- [Project Loom FAQ](https://wiki.openjdk.org/display/loom)
