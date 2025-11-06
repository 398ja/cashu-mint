# Observability Strategy for Cashu Ecosystem

**Date**: 2025-11-06
**Status**: Strategic Planning
**Scope**: All Cashu components (mint, wallet, voucher, client)

---

## Executive Summary

**Question**: Should we add observability (Micrometer/OpenTelemetry) to cashu-mint and other Cashu components after adding it to cashu-client?

**Answer**: **YES - with prioritization** ✅

Observability is critical for production systems, but not all components have equal requirements. This document provides a strategic roadmap for implementing observability across the Cashu ecosystem.

---

## Component Analysis

### 1. cashu-client ✅ (Already Done)

**Current State**: Micrometer + OpenTelemetry observability added

**Justification**:
- ✅ Long-running service/daemon process
- ✅ Complex user workflows (send, receive, proofs)
- ✅ Network interactions with multiple mints
- ✅ Performance-sensitive (user-facing)
- ✅ Production deployment typical

**Observability Value**: **CRITICAL** 🔴

---

### 2. cashu-mint 🔴 (HIGH PRIORITY)

**Current State**: No observability (needs implementation)

**Component Structure**:
```
cashu-mint/
├── cashu-mint-protocol/    # Core mint logic
├── cashu-mint-rest/         # REST API layer
└── cashu-mint-tools/        # Admin utilities
```

#### Why Observability is CRITICAL for Mint

1. **Server-side Service** 🌐
   - Long-running process serving multiple users
   - 24/7 availability requirement
   - Production deployment is the norm

2. **Financial Operations** 💰
   - Handles real money (Bitcoin ecash)
   - Security-critical operations (blind signatures)
   - Fraud detection requirements
   - Double-spend prevention

3. **Performance Requirements** ⚡
   - Target: 1000+ vouchers/sec per core (per benchmarks)
   - Latency-sensitive (< 100ms response time)
   - Concurrent user handling
   - Resource constraints (memory, CPU)

4. **Operational Complexity** 🔧
   - Key rotation and management
   - Database operations (proofs, keysets)
   - Lightning Network integration
   - Bitcoin on-chain operations

5. **Debugging and Troubleshooting** 🐛
   - Need to trace failed mint operations
   - Identify performance bottlenecks
   - Monitor resource exhaustion
   - Track error rates and types

#### What to Monitor in cashu-mint

**Metrics** (Quantitative):
```
# Request Metrics
mint.requests.total                    # Counter - Total requests
mint.requests.duration                 # Timer - Request latency
mint.requests.errors                   # Counter - Failed requests
mint.requests.by_endpoint              # Counter - Per-endpoint metrics

# Mint Operations
mint.proofs.issued                     # Counter - Proofs issued
mint.proofs.verified                   # Counter - Proofs verified
mint.proofs.double_spend_detected      # Counter - Double-spend attempts
mint.blind_signatures.generated        # Counter - Signatures created
mint.blind_signatures.verified         # Counter - Signatures verified

# Business Metrics
mint.sats.issued                       # Counter - Total sats issued
mint.sats.redeemed                     # Counter - Total sats redeemed
mint.sats.outstanding                  # Gauge - Current liability
mint.keysets.active                    # Gauge - Active keysets
mint.keysets.rotations                 # Counter - Key rotations

# Performance Metrics
mint.proof_verification.duration       # Timer - Verification time
mint.signature_generation.duration     # Timer - Signing time
mint.database.query.duration           # Timer - DB query time
mint.lightning.invoice.duration        # Timer - LN operations

# Resource Metrics
mint.memory.used                       # Gauge - Heap usage
mint.threads.active                    # Gauge - Thread pool
mint.connections.active                # Gauge - HTTP connections
mint.database.connections.active       # Gauge - DB connections

# Security Metrics
mint.auth.failures                     # Counter - Auth failures
mint.rate_limit.exceeded               # Counter - Rate limit hits
mint.suspicious_patterns.detected      # Counter - Fraud attempts

# Voucher-Specific (if integrated)
mint.vouchers.rejected                 # Counter - Model B rejections
mint.vouchers.detected_in_swap         # Counter - Voucher in swap
```

**Traces** (Distributed Tracing):
```
Trace: POST /v1/mint/quote
  ├─ Span: validate_request
  ├─ Span: generate_invoice (Lightning)
  ├─ Span: store_quote (Database)
  └─ Span: return_response

Trace: POST /v1/swap
  ├─ Span: parse_request
  ├─ Span: verify_proofs
  │   ├─ Span: check_double_spend (Database)
  │   ├─ Span: verify_signatures (Crypto)
  │   └─ Span: validate_amounts
  ├─ Span: generate_blind_signatures
  ├─ Span: mark_proofs_spent (Database)
  └─ Span: return_response
```

**Logs** (Contextual):
```
INFO  [mint.swap] Swap request received inputs=5 outputs=3 total_amount=1000
DEBUG [mint.proof] Verifying proof id=abc123 amount=100
WARN  [mint.proof] Double-spend attempt detected proof_id=xyz789
ERROR [mint.signature] Signature verification failed proof_id=def456
```

#### Implementation Priority: **HIGH** 🔴

**Estimated Effort**: 3-5 days
- Add Micrometer dependencies
- Instrument REST endpoints (cashu-mint-rest)
- Instrument protocol operations (cashu-mint-protocol)
- Add custom metrics for mint operations
- Configure exporters (Prometheus/OTLP)
- Write observability guide

**Recommendation**: **Implement in next sprint** after v0.1.0 voucher release

---

### 3. cashu-wallet 🟡 (MEDIUM PRIORITY)

**Current State**: No observability

**Component Structure**:
```
cashu-wallet/
├── cashu-wallet-protocol/   # Wallet logic (NUT-13)
└── cashu-wallet-client/      # CLI interface
```

#### Why Observability is IMPORTANT for Wallet

1. **Long-Running Process** (if daemon mode)
   - Background processes for notifications
   - Continuous mint synchronization
   - Proof management

2. **User Experience** 😊
   - Track operation success rates
   - Monitor response times (UX metric)
   - Identify slow operations

3. **Debugging** 🐛
   - Trace failed send/receive operations
   - Monitor NUT-13 recovery performance
   - Track proof state transitions

4. **Security** 🔒
   - Monitor for suspicious activity
   - Track seed phrase usage
   - Detect key derivation issues

#### What to Monitor in cashu-wallet

**Metrics**:
```
# Wallet Operations
wallet.balance.total                   # Gauge - Current balance
wallet.balance.by_mint                 # Gauge - Per-mint balance
wallet.proofs.count                    # Gauge - Number of proofs
wallet.transactions.total              # Counter - Total txs
wallet.transactions.failed             # Counter - Failed txs

# Performance
wallet.send.duration                   # Timer - Send operation time
wallet.receive.duration                # Timer - Receive operation time
wallet.sync.duration                   # Timer - Sync time with mint

# NUT-13 Recovery
wallet.recovery.attempts               # Counter - Recovery attempts
wallet.recovery.proofs_found           # Counter - Proofs recovered
wallet.recovery.duration               # Timer - Recovery time

# Vouchers (if integrated)
wallet.vouchers.issued                 # Counter - Vouchers created
wallet.vouchers.redeemed               # Counter - Vouchers used
wallet.vouchers.backed_up              # Counter - Nostr backups
wallet.vouchers.restored               # Counter - Nostr restores
```

**Traces**:
```
Trace: cashu send 1000
  ├─ Span: select_proofs
  ├─ Span: create_token
  ├─ Span: swap_with_mint (if needed)
  └─ Span: encode_token

Trace: cashu receive <token>
  ├─ Span: decode_token
  ├─ Span: verify_signatures
  ├─ Span: swap_with_mint
  └─ Span: store_proofs
```

#### Implementation Priority: **MEDIUM** 🟡

**Estimated Effort**: 2-3 days

**Recommendation**: **Implement in v0.2.0 or v0.3.0**

---

### 4. cashu-voucher 🟢 (LOW PRIORITY - Domain Library)

**Current State**: No observability (performance benchmarks exist)

**Component Type**: **Library** (not a service)

#### Why Observability is LESS CRITICAL for Voucher

1. **Library, Not Service** 📚
   - Consumed by other services (mint, wallet)
   - No standalone deployment
   - No long-running process

2. **Observability at Integration Points** 🔌
   - Mint observes voucher rejection (already in mint metrics)
   - Wallet observes voucher operations (already in wallet metrics)
   - Library users add their own instrumentation

3. **Performance Already Measured** ⚡
   - JMH benchmarks provide detailed performance data
   - 17 benchmarks covering all operations
   - Performance report documents characteristics

#### What to Monitor (if needed)

**Limited Instrumentation** (Optional):
```
# Add only if performance issues arise
voucher.signature.duration             # Timer - Sign operations
voucher.verification.duration          # Timer - Verify operations
voucher.serialization.duration         # Timer - CBOR operations
voucher.validation.failures            # Counter - Validation errors
```

**Where to Add**:
- VoucherSignatureService (crypto operations)
- VoucherValidator (validation operations)
- Nostr repositories (I/O operations)

#### Implementation Priority: **LOW** 🟢

**Estimated Effort**: 1 day

**Recommendation**:
- **NOT needed for v0.1.0** ✅
- Add only if adopted services (mint, wallet) report performance issues
- Focus observability on consuming services, not the library

**Rationale**:
- Libraries don't have "operational" metrics (no requests, no users)
- Integration points (mint, wallet) should add metrics for voucher operations
- JMH benchmarks already provide performance insights
- Adding observability to a library adds dependency overhead

---

### 5. cashu-mint-admin 🟡 (LOW-MEDIUM PRIORITY)

**Current State**: Unknown

**Purpose**: Administrative tools for mint operators

#### Observability Needs

If it's a CLI tool:
- **LOW priority** (similar to wallet CLI)
- Add if it becomes a long-running admin dashboard

If it's a web dashboard:
- **MEDIUM priority**
- Track admin operations (key rotation, config changes)
- Monitor security-critical actions

**Recommendation**: Assess based on actual use case

---

## Implementation Strategy

### Phase 1: Server-Side Services (Immediate) 🔴

**Priority**: cashu-mint

**Timeline**: Next sprint (1-2 weeks)

**Dependencies**:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

**Steps**:
1. Add Micrometer to cashu-mint-rest
2. Instrument REST endpoints
3. Add custom metrics for mint operations
4. Configure Prometheus exporter
5. Add Grafana dashboard templates
6. Document metrics and alerts

---

### Phase 2: Client-Side Applications (Next Quarter) 🟡

**Priority**: cashu-wallet

**Timeline**: v0.2.0 or v0.3.0 (1-2 months)

**Rationale**:
- Client observability less critical than server
- Wallet is more development/testing focused
- Users can opt-in to telemetry

**Steps**:
1. Add Micrometer to wallet CLI
2. Instrument wallet operations
3. Add opt-in telemetry flag
4. Respect user privacy (no PII)

---

### Phase 3: Libraries (As Needed) 🟢

**Priority**: cashu-voucher (only if needed)

**Timeline**: v1.0.0+ (6+ months) or never

**Recommendation**:
- Monitor voucher operations via mint/wallet metrics
- Only add library instrumentation if performance issues arise
- JMH benchmarks are sufficient for now

---

## Technology Stack Recommendation

### Option 1: Micrometer (Recommended) ✅

**Why**:
- Already in cashu-client
- Vendor-neutral (works with Prometheus, OTLP, etc.)
- Java standard (Spring Boot uses it)
- Mature and well-documented

**Dependencies**:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <version>1.14.2</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.14.2</version>
</dependency>
```

### Option 2: OpenTelemetry (Alternative)

**Why**:
- Industry standard (CNCF)
- Full observability stack (metrics, traces, logs)
- Language-agnostic

**When to use**:
- If adopting distributed tracing across all services
- If already using OTLP collectors

---

## Observability vs Performance Trade-offs

### Performance Impact

| Component | Overhead | Acceptable? |
|-----------|----------|-------------|
| **Metrics Collection** | < 1% CPU | ✅ Yes |
| **Distributed Tracing (sampled)** | < 2% CPU | ✅ Yes |
| **Distributed Tracing (100%)** | 10-20% CPU | ❌ No |
| **Verbose Logging** | 5-15% CPU | ⚠️ Depends |

### Recommendations

1. **Sampling for Traces** 📊
   - Production: 1-5% sampling
   - Staging: 10-50% sampling
   - Development: 100% sampling

2. **Metric Cardinality** 🔢
   - Avoid high-cardinality labels (user IDs, proof IDs)
   - Use bounded labels (endpoint, status code, error type)
   - Limit to < 1000 unique time series per metric

3. **Log Levels** 📝
   - Production: INFO or WARN
   - Staging: DEBUG
   - Development: TRACE

---

## Monitoring Dashboards

### Mint Dashboard (Grafana)

**Key Panels**:
1. Request Rate & Latency (RED metrics)
2. Error Rate by Endpoint
3. Mint Operations (issued, redeemed, outstanding sats)
4. Double-Spend Detection
5. Resource Usage (CPU, memory, threads)
6. Lightning Integration Status

### Wallet Dashboard (Grafana)

**Key Panels**:
1. Transaction Success Rate
2. Operation Latency (send, receive, sync)
3. Balance Over Time
4. Proof Management
5. NUT-13 Recovery Metrics

---

## Alerting Strategy

### Critical Alerts (Page Operator) 🚨

```yaml
# cashu-mint alerts
- alert: MintHighErrorRate
  expr: rate(mint_requests_errors[5m]) > 0.05
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Mint error rate above 5%"

- alert: MintHighLatency
  expr: histogram_quantile(0.99, mint_requests_duration) > 1.0
  for: 10m
  labels:
    severity: critical
  annotations:
    summary: "Mint P99 latency above 1 second"

- alert: DoubleSpendsDetected
  expr: increase(mint_proofs_double_spend_detected[5m]) > 10
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "Unusual double-spend activity"

- alert: MintOutOfMemory
  expr: mint_memory_used / mint_memory_max > 0.9
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Mint using over 90% memory"
```

### Warning Alerts (Notify Operator) ⚠️

```yaml
- alert: MintSlowQueries
  expr: histogram_quantile(0.95, mint_database_query_duration) > 0.5
  for: 15m
  labels:
    severity: warning

- alert: MintHighCPU
  expr: process_cpu_usage > 0.8
  for: 15m
  labels:
    severity: warning
```

---

## Privacy Considerations

### What NOT to Track 🔒

**Never collect**:
- Seed phrases or private keys
- User IP addresses (unless essential)
- Personally Identifiable Information (PII)
- Proof secrets or preimages
- Full transaction details

### What IS Safe to Track ✅

- Aggregated metrics (counts, rates, percentiles)
- Error types and counts
- Performance metrics (duration, throughput)
- Resource usage (CPU, memory)
- Business metrics (amounts, counts) - **aggregated only**

### Privacy-Safe Examples

❌ **BAD**:
```java
metrics.counter("wallet.send",
    "user_id", userId,           // PII
    "destination", destination,  // PII
    "amount", amount)            // Sensitive
```

✅ **GOOD**:
```java
metrics.counter("wallet.send.total").increment();
metrics.timer("wallet.send.duration").record(duration);
metrics.counter("wallet.send.errors",
    "error_type", errorType).increment();
```

---

## Cost Analysis

### Infrastructure Costs

| Component | Monthly Cost (AWS) | Notes |
|-----------|-------------------|-------|
| **Prometheus** | $10-50 | Self-hosted on EC2 t3.small |
| **Grafana** | $0 (self-hosted) | Or $49/mo for Grafana Cloud |
| **OTLP Collector** | $10-30 | If using distributed tracing |
| **Storage** | $5-20 | Metrics retention (30 days) |
| **Total** | **$25-149/mo** | Depending on scale |

### Cheaper Alternatives

1. **Self-hosted Prometheus + Grafana** - $15-30/mo
2. **Grafana Cloud Free Tier** - $0 (limited)
3. **Honeycomb Free Tier** - $0 (limited traces)

---

## Recommendations Summary

### Immediate Actions (v0.1.0+)

1. ✅ **cashu-mint** - Add full observability (HIGH PRIORITY)
   - Effort: 3-5 days
   - Start: Next sprint
   - Dependencies: Micrometer, Prometheus

2. ❌ **cashu-voucher** - NO observability needed (library)
   - JMH benchmarks sufficient
   - Consuming services will add their own metrics

### Future Actions (v0.2.0+)

3. 🟡 **cashu-wallet** - Add observability (MEDIUM PRIORITY)
   - Effort: 2-3 days
   - Start: v0.2.0 or v0.3.0

4. 🟡 **cashu-mint-admin** - Assess based on use case
   - If CLI: LOW priority
   - If dashboard: MEDIUM priority

### Long-Term (v1.0.0+)

5. 🌍 **Distributed Tracing** - Add OTLP tracing across all services
   - Trace requests across mint, wallet, Lightning
   - Identify bottlenecks in distributed flows

6. 📊 **Advanced Analytics** - Business intelligence dashboards
   - Track ecosystem growth (total sats, users)
   - Monitor adoption metrics
   - Identify usage patterns

---

## Next Steps

### For cashu-mint (HIGH PRIORITY)

1. **Create JIRA ticket**: "Add observability to cashu-mint"
2. **Assign to**: [Developer]
3. **Timeline**: Next sprint (1-2 weeks)
4. **Deliverables**:
   - Micrometer integration
   - REST endpoint instrumentation
   - Mint operation metrics
   - Prometheus exporter
   - Grafana dashboard JSON
   - Observability documentation

### For cashu-wallet (MEDIUM PRIORITY)

1. **Create JIRA ticket**: "Add observability to cashu-wallet"
2. **Assign to**: [Developer]
3. **Timeline**: v0.2.0 (Q1 2026)
4. **Deliverables**:
   - Micrometer integration
   - Wallet operation metrics
   - Opt-in telemetry flag
   - Privacy-safe metrics only

### For cashu-voucher (LOW PRIORITY)

1. **Decision**: NO immediate action
2. **Monitor**: Track voucher performance via mint/wallet metrics
3. **Reassess**: Only if performance issues reported

---

## Conclusion

**Answer to Original Question**:

> **YES, add observability to cashu-mint immediately** 🔴
> - It's a server-side service with production requirements
> - Handles financial operations (critical monitoring)
> - Performance and security require visibility
>
> **YES, add observability to cashu-wallet eventually** 🟡
> - Medium priority for v0.2.0+
> - User experience and debugging benefits
>
> **NO, don't add observability to cashu-voucher library** 🟢
> - Libraries don't need observability at the library level
> - Consuming services (mint, wallet) should add metrics
> - JMH benchmarks already provide performance insights

**Strategic Approach**:
1. Server-first (mint) ← **Start here**
2. Client-second (wallet) ← v0.2.0
3. Libraries-last (voucher) ← Only if needed

---

**Document Version**: 1.0
**Last Updated**: 2025-11-06
**Next Review**: After cashu-mint observability implementation
