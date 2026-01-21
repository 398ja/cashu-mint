# Phase 1 Pilot Results

**Date:** YYYY-MM-DD
**Tester:** [Name]
**cashu-mint Version:** [Version/Commit]
**Java Version:** 21.x.x

## Test Environment

| Attribute | Value |
|-----------|-------|
| CPU | [e.g., Intel i7-12700 / 8 cores] |
| RAM | [e.g., 32GB] |
| OS | [e.g., Ubuntu 24.04] |
| JVM Heap | [e.g., 2GB] |
| GC | [e.g., G1GC] |

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Duration | [e.g., 300s] |
| Ramp-up VUs | [e.g., 25 → 50 → 100] |
| Target Endpoint | [e.g., /v1/info, /v1/keysets] |
| Load Test Tool | k6 vX.X.X |

## Baseline Results (VT Disabled)

| Metric | Value |
|--------|-------|
| Total Requests | |
| Throughput (req/s) | |
| p50 Latency (ms) | |
| p95 Latency (ms) | |
| p99 Latency (ms) | |
| Error Rate (%) | |
| Max Memory (MB) | |
| Avg CPU (%) | |

## Virtual Thread Results (VT Enabled)

| Metric | Value |
|--------|-------|
| Total Requests | |
| Throughput (req/s) | |
| p50 Latency (ms) | |
| p95 Latency (ms) | |
| p99 Latency (ms) | |
| Error Rate (%) | |
| Max Memory (MB) | |
| Avg CPU (%) | |

## Comparison

| Metric | Baseline | VT Enabled | Change | Pass/Fail |
|--------|----------|------------|--------|-----------|
| Throughput (req/s) | | | % | |
| p50 Latency (ms) | | | % | |
| p95 Latency (ms) | | | % | |
| p99 Latency (ms) | | | % | |
| Error Rate (%) | | | % | |
| Memory (MB) | | | % | |

## Pinning Analysis

### Pinning Events Detected

- [ ] No pinning events detected
- [ ] Pinning events detected (see details below)

#### Pinning Event Details

| Event # | Stack Trace Summary | Code Location | Severity |
|---------|---------------------|---------------|----------|
| 1 | | | |
| 2 | | | |

### JFR Analysis

| Check | Result | Notes |
|-------|--------|-------|
| VirtualThreadPinned events | [None/Count] | |
| Lock contention hotspots | | |
| Blocking time increase | | |
| GC pause increase | | |

## Go/No-Go Assessment

### Success Criteria Checklist

| Criteria | Target | Actual | Pass |
|----------|--------|--------|------|
| Throughput | ≥ baseline | | [ ] |
| p95 Latency | ≤ baseline | | [ ] |
| p99 Latency | ≤ 110% baseline | | [ ] |
| Error Rate | ≤ baseline | | [ ] |
| VT Pinning (hot paths) | 0 | | [ ] |

### Mandatory No-Go Conditions

- [ ] No VirtualThreadPinned events in mint/melt/swap paths
- [ ] No double-mint/double-spend in concurrent tests
- [ ] No OOM errors during load test

## Decision

**Result:** [ ] GO / [ ] NO-GO

**Rationale:**
[Explain the decision based on the data above]

## Issues Found

| Issue | Severity | Description | Action |
|-------|----------|-------------|--------|
| | | | |

## Recommendations

1. [Recommendation 1]
2. [Recommendation 2]

## Next Steps

- [ ] Proceed to Phase 2: Pool Tuning
- [ ] Address issues before proceeding
- [ ] Re-run pilot after fixes

## Artifacts

| Artifact | Location |
|----------|----------|
| Baseline JFR | `results/baseline-YYYYMMDD.jfr` |
| VT JFR | `results/vt-enabled-YYYYMMDD.jfr` |
| Baseline k6 results | `results/baseline-results.json` |
| VT k6 results | `results/vt-results.json` |
| Pinning log | `results/vt-pinning.log` |
