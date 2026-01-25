# Phase 1: Virtual Thread Pilot Guide

This guide provides step-by-step instructions for running the Virtual Thread pilot experiment.

## Prerequisites

- Java 21+
- Docker (for k6 load testing)
- jq (for JSON processing)
- Built cashu-mint-rest JAR

```bash
# Build the project
mvn clean package -DskipTests

# Verify Java version
java -version  # Must be 21+
```

## Step 1: Capture Baseline Metrics (VT Disabled)

First, run the load test with virtual threads **disabled** to establish baseline performance.

### 1.1 Start the Mint with VT Disabled

```bash
# Start with virtual threads disabled
java \
  -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=filename=results/baseline.jfr,duration=300s,settings=profile \
  -Dspring.threads.virtual.enabled=false \
  -jar cashu-mint-rest/target/cashu-mint-rest-*-exec.jar
```

Wait for the application to start (check `http://localhost:7777/v1/info`).

### 1.2 Run Baseline Load Test

```bash
# Create results directory
mkdir -p results

# Run k6 load test (via Docker)
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v $(pwd)/scripts:/scripts \
  -v $(pwd)/results:/results \
  grafana/k6 run \
  --out json=/results/baseline-results.json \
  /scripts/load-test-mint.js

# Stop the application
# Press Ctrl+C or kill the process
```

### 1.3 Save Baseline JFR

```bash
# Move JFR recording
mv results/baseline.jfr results/baseline-$(date +%Y%m%d-%H%M%S).jfr
```

## Step 2: Run with Virtual Threads Enabled

Now run the same test with virtual threads **enabled**.

### 2.1 Start the Mint with VT Enabled

```bash
# Start with virtual threads enabled (default) and pinning detection
java \
  -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=filename=results/vt-enabled.jfr,duration=300s,settings=profile \
  -Djdk.tracePinnedThreads=full \
  -Dspring.threads.virtual.enabled=true \
  -jar cashu-mint-rest/target/cashu-mint-rest-*-exec.jar \
  2>&1 | tee results/vt-pinning.log
```

Watch the console for pinning warnings like:
```
Thread[#XX,VirtualThread-unparker,5,CarrierThreads] org.example.SomeClass.method(SomeClass.java:123) <== monitors:1
```

### 2.2 Run VT Load Test

```bash
# Run k6 load test
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v $(pwd)/scripts:/scripts \
  -v $(pwd)/results:/results \
  grafana/k6 run \
  --out json=/results/vt-results.json \
  /scripts/load-test-mint.js

# Stop the application
```

## Step 3: Compare Results

### 3.1 Run Comparison Script

```bash
./scripts/compare-vt-performance.sh results/baseline-results.json results/vt-results.json
```

Expected output:
```
==============================================
  Virtual Thread Performance Comparison
==============================================

=== Request Throughput (req/s) ===
  Baseline: 150.5
  Virtual:  165.2
  Change:   9.77%

=== Latency p95 (ms) ===
  Baseline: 45.2
  Virtual:  38.1

=== Latency p99 (ms) ===
  Baseline: 89.3
  Virtual:  72.5
  Change:   -18.81%

==============================================
  Summary
==============================================
  Decision: GO
```

### 3.2 Check for Pinning Events

```bash
# Check pinning log
grep -i "pinned\|monitors" results/vt-pinning.log

# If pinning events are found, note the stack traces for investigation
```

## Step 4: Analyze JFR Recordings

### 4.1 Open in JDK Mission Control

```bash
# Install JMC if not available
# On Ubuntu: sudo apt install openjdk-21-jmc
# On macOS: brew install --cask jdk-mission-control

jmc results/vt-enabled.jfr
```

### 4.2 Key Metrics to Check

| Metric | Where to Find | What to Look For |
|--------|---------------|------------------|
| Thread Count | Threading > Thread Count | VT count vs platform threads |
| CPU Usage | General > CPU Load | Should not increase significantly |
| Blocking Time | Method Profiling | Hot blocking methods |
| Lock Contention | Lock Instances | High contention points |
| GC Pauses | Garbage Collection | Increased GC with VTs |

### 4.3 Check for Pinning in JFR

In JMC, look for `jdk.VirtualThreadPinned` events:
1. Open the JFR file
2. Go to "Event Browser"
3. Search for "VirtualThreadPinned"
4. Review stack traces of pinned threads

## Step 5: Document Results

Fill in the [pilot-results.md](pilot-results.md) template with your findings.

## Troubleshooting

### No Performance Improvement

If throughput doesn't improve:
1. Check if the workload is CPU-bound (VTs don't help CPU work)
2. Verify VTs are actually enabled: look for "Virtual threads" in startup logs
3. Check if global locks are serializing requests

### Pinning Events Detected

If pinning events occur:
1. Note the stack trace and class name
2. Check if it's in application code or a library
3. For libraries: check if a newer version fixes the issue
4. For application code: refactor `synchronized` to `ReentrantLock`

### Memory Issues

If OOM or high memory usage:
1. Run the heap exhaustion test: `./scripts/heap-exhaustion-test.sh`
2. Increase heap size: `-Xmx4g`
3. Check for thread-local leaks in JFR

## Go/No-Go Criteria

Based on [loom-assessment.md](../../project/loom-assessment.md):

| Metric | Go Criteria |
|--------|-------------|
| Throughput | ≥ baseline |
| p95 Latency | ≤ baseline |
| p99 Latency | ≤ 110% baseline |
| Error Rate | ≤ baseline |
| VT Pinning | 0 in hot paths |

**Mandatory No-Go:**
- Any `VirtualThreadPinned` events in mint/melt/swap code paths
- Double-mint or double-spend in concurrent tests
- OOM errors during load test

## Next Steps

If pilot is successful (GO decision):
- Proceed to Phase 2: Pool Tuning
- Update loom-assessment.md with results

If pilot fails (NO-GO decision):
- Document the failure reason
- Investigate and address the issue
- Re-run the pilot
