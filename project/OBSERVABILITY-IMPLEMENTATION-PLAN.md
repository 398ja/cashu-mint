# Cashu Mint Observability Implementation Plan

**Date**: 2025-11-29
**Status**: ✅ Complete
**Module**: `cashu-mint-observability`
**Reference**: [OBSERVABILITY-STRATEGY.md](./OBSERVABILITY-STRATEGY.md)

---

## 1. Overview

This plan implements full observability for cashu-mint as outlined in the observability strategy. The implementation adds Prometheus metrics, distributed tracing, and Grafana dashboards through a new independent Maven module.

### Goals
- Add Micrometer-based metrics collection
- Expose Prometheus-compatible `/actuator/prometheus` endpoint
- Instrument all NUT protocol operations and tasks
- Provide pre-built Grafana dashboards
- Include Docker infrastructure for local observability stack
- Maintain < 1% performance overhead

### Non-Goals
- OpenTelemetry distributed tracing (Phase 2)
- Custom alerting rules (operator responsibility)
- Production infrastructure provisioning

---

## 2. Module Structure

### 2.1 New Module: `cashu-mint-observability`

```
cashu-mint-observability/
├── pom.xml
├── src/
│   └── main/
│       ├── java/xyz/tcheeric/cashu/mint/observability/
│       │   ├── config/
│       │   │   ├── ObservabilityAutoConfiguration.java
│       │   │   └── MetricsConfiguration.java
│       │   ├── metrics/
│       │   │   ├── MintMetrics.java              # Core mint operation metrics
│       │   │   ├── TaskMetrics.java              # Task execution metrics
│       │   │   ├── ProofMetrics.java             # Proof lifecycle metrics
│       │   │   ├── QuoteMetrics.java             # Quote operation metrics
│       │   │   ├── VoucherMetrics.java           # Voucher-specific metrics
│       │   │   └── GatewayMetrics.java           # Lightning gateway metrics
│       │   ├── aspect/
│       │   │   ├── TaskTimingAspect.java         # AOP for task instrumentation
│       │   │   └── ControllerTimingAspect.java   # AOP for REST endpoints
│       │   ├── interceptor/
│       │   │   └── MetricsHandlerInterceptor.java
│       │   └── health/
│       │       ├── GatewayHealthIndicator.java
│       │       └── VaultHealthIndicator.java
│       └── resources/
│           └── META-INF/
│               └── spring/
│                   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── docker/
│   ├── prometheus/
│   │   └── prometheus.yml
│   ├── grafana/
│   │   ├── provisioning/
│   │   │   ├── datasources/
│   │   │   │   └── prometheus.yml
│   │   │   └── dashboards/
│   │   │       └── dashboards.yml
│   │   └── dashboards/
│       │   ├── cashu-mint-overview.json
│       │   ├── cashu-mint-operations.json
│       │   └── cashu-mint-business.json
│   └── docker-compose.observability.yml
└── docs/
    └── metrics-reference.md
```

### 2.2 Parent POM Updates

Add to `cashu-mint/pom.xml` modules list:
```xml
<module>cashu-mint-observability</module>
```

Add version property:
```xml
<micrometer.version>1.14.2</micrometer.version>
```

### 2.3 Module POM: `cashu-mint-observability/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-mint</artifactId>
        <version>0.4.0</version>
    </parent>

    <artifactId>cashu-mint-observability</artifactId>
    <name>cashu-mint-observability</name>
    <description>Observability module for Cashu Mint (Prometheus, Grafana)</description>

    <dependencies>
        <!-- Spring Boot Actuator (base) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Micrometer Prometheus Registry -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Micrometer Core -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
        </dependency>

        <!-- AOP for instrumentation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>

        <!-- Protocol module for task instrumentation -->
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>cashu-mint-protocol</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 3. Metrics Definitions

### 3.1 Request Metrics (RED Pattern)

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_requests_total` | Counter | `endpoint`, `method`, `status` | Total HTTP requests |
| `cashu_mint_requests_duration_seconds` | Timer | `endpoint`, `method` | Request latency histogram |
| `cashu_mint_requests_errors_total` | Counter | `endpoint`, `error_type` | Failed requests |

### 3.2 Mint Operations

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_proofs_issued_total` | Counter | `keyset_id`, `unit` | Proofs issued |
| `cashu_mint_proofs_spent_total` | Counter | `keyset_id`, `unit` | Proofs marked spent |
| `cashu_mint_proofs_double_spend_total` | Counter | `keyset_id` | Double-spend attempts detected |
| `cashu_mint_signatures_generated_total` | Counter | `keyset_id` | Blind signatures created |
| `cashu_mint_signatures_verified_total` | Counter | `keyset_id` | Signatures verified |

### 3.3 Business Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_sats_issued_total` | Counter | `unit`, `method` | Total sats issued |
| `cashu_mint_sats_redeemed_total` | Counter | `unit`, `method` | Total sats redeemed |
| `cashu_mint_sats_outstanding` | Gauge | `unit` | Current liability (issued - redeemed) |
| `cashu_mint_keysets_active` | Gauge | `unit` | Active keyset count |
| `cashu_mint_quotes_created_total` | Counter | `type`, `method` | Quotes created (mint/melt) |
| `cashu_mint_quotes_completed_total` | Counter | `type`, `method` | Quotes completed |

### 3.4 Task Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_task_duration_seconds` | Timer | `task_name` | Task execution time |
| `cashu_mint_task_success_total` | Counter | `task_name` | Successful task executions |
| `cashu_mint_task_failure_total` | Counter | `task_name`, `error_type` | Failed task executions |

Tasks to instrument:
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

### 3.5 Voucher Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_vouchers_issued_total` | Counter | `unit` | Vouchers issued |
| `cashu_mint_vouchers_redeemed_total` | Counter | `unit` | Vouchers redeemed |
| `cashu_mint_vouchers_rejected_total` | Counter | `reason` | Voucher rejections |
| `cashu_mint_voucher_quote_fee_sats_total` | Counter | - | Total fees collected from voucher quotes |

### 3.6 Gateway/Infrastructure Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cashu_mint_gateway_request_duration_seconds` | Timer | `operation` | Gateway call latency |
| `cashu_mint_gateway_errors_total` | Counter | `operation`, `error_type` | Gateway errors |
| `cashu_mint_vault_request_duration_seconds` | Timer | `operation` | Vault call latency |

---

## 4. Implementation Components

### 4.1 Auto-Configuration

```java
// ObservabilityAutoConfiguration.java
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class})
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MintMetrics mintMetrics(MeterRegistry registry) {
        return new MintMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskMetrics taskMetrics(MeterRegistry registry) {
        return new TaskMetrics(registry);
    }

    // Additional beans...
}
```

### 4.2 Core Metrics Class

```java
// MintMetrics.java
@Component
public class MintMetrics {
    private final Counter proofsIssued;
    private final Counter proofsSpent;
    private final Counter doubleSpendAttempts;
    private final AtomicLong satsOutstanding;

    public MintMetrics(MeterRegistry registry) {
        this.proofsIssued = Counter.builder("cashu_mint_proofs_issued_total")
            .description("Total proofs issued")
            .tag("unit", "sat")
            .register(registry);

        this.proofsSpent = Counter.builder("cashu_mint_proofs_spent_total")
            .description("Total proofs marked as spent")
            .tag("unit", "sat")
            .register(registry);

        this.doubleSpendAttempts = Counter.builder("cashu_mint_proofs_double_spend_total")
            .description("Double-spend attempts detected")
            .register(registry);

        this.satsOutstanding = registry.gauge("cashu_mint_sats_outstanding",
            Tags.of("unit", "sat"),
            new AtomicLong(0));
    }

    public void recordProofIssued(String keysetId, long amount) {
        proofsIssued.increment();
        satsOutstanding.addAndGet(amount);
    }

    public void recordProofSpent(String keysetId, long amount) {
        proofsSpent.increment();
        satsOutstanding.addAndGet(-amount);
    }

    public void recordDoubleSpendAttempt() {
        doubleSpendAttempts.increment();
    }
}
```

### 4.3 Task Instrumentation Aspect

```java
// TaskTimingAspect.java
@Aspect
@Component
@ConditionalOnProperty(name = "cashu.observability.tasks.enabled", havingValue = "true", matchIfMissing = true)
public class TaskTimingAspect {

    private final TaskMetrics taskMetrics;

    @Around("execution(* xyz.tcheeric.cashu.mint.proto.tasks.*.execute(..))")
    public Object timeTaskExecution(ProceedingJoinPoint pjp) throws Throwable {
        String taskName = pjp.getTarget().getClass().getSimpleName();
        Timer.Sample sample = Timer.start();

        try {
            Object result = pjp.proceed();
            taskMetrics.recordSuccess(taskName);
            return result;
        } catch (Exception e) {
            taskMetrics.recordFailure(taskName, e.getClass().getSimpleName());
            throw e;
        } finally {
            sample.stop(taskMetrics.getTimer(taskName));
        }
    }
}
```

### 4.4 Controller Interceptor

```java
// MetricsHandlerInterceptor.java
@Component
public class MetricsHandlerInterceptor implements HandlerInterceptor {

    private final MeterRegistry registry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("metricsStartTime", System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute("metricsStartTime");
        if (startTime != null) {
            long duration = System.nanoTime() - startTime;
            String endpoint = normalizeEndpoint(request.getRequestURI());

            Timer.builder("cashu_mint_requests_duration_seconds")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry)
                .record(duration, TimeUnit.NANOSECONDS);

            Counter.builder("cashu_mint_requests_total")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry)
                .increment();
        }
    }

    private String normalizeEndpoint(String uri) {
        // Normalize variable path segments to avoid high cardinality
        return uri.replaceAll("/v1/keys/[^/]+", "/v1/keys/{keyset_id}")
                  .replaceAll("/v1/mint/quote/[^/]+/[^/]+", "/v1/mint/quote/{method}/{quote_id}")
                  .replaceAll("/v1/melt/quote/[^/]+/[^/]+", "/v1/melt/quote/{method}/{quote_id}");
    }
}
```

### 4.5 Health Indicators

```java
// GatewayHealthIndicator.java
@Component
@ConditionalOnProperty(name = "cashu.observability.health.gateway.enabled", havingValue = "true", matchIfMissing = true)
public class GatewayHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            // Check gateway connectivity
            boolean gatewayReachable = checkGatewayHealth();
            if (gatewayReachable) {
                return Health.up()
                    .withDetail("gateway", "connected")
                    .build();
            } else {
                return Health.down()
                    .withDetail("gateway", "unreachable")
                    .build();
            }
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

---

## 5. Configuration Properties

### 5.1 Application Properties

Add to `cashu-mint-rest/src/main/resources/application.properties`:

```properties
# Observability Configuration
# Enable Prometheus endpoint
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.prometheus.enabled=true
management.metrics.export.prometheus.enabled=true

# Metric tags (common labels)
management.metrics.tags.application=cashu-mint
management.metrics.tags.env=${ENVIRONMENT:local}

# Task instrumentation
cashu.observability.tasks.enabled=true

# Health indicators
cashu.observability.health.gateway.enabled=true
cashu.observability.health.vault.enabled=true

# Histogram buckets for latency (in seconds)
management.metrics.distribution.slo.cashu_mint_requests_duration_seconds=0.01,0.05,0.1,0.25,0.5,1.0,2.5,5.0,10.0
management.metrics.distribution.slo.cashu_mint_task_duration_seconds=0.001,0.005,0.01,0.025,0.05,0.1,0.25,0.5,1.0

# Enable percentiles for timers
management.metrics.distribution.percentiles-histogram.cashu_mint_requests_duration_seconds=true
management.metrics.distribution.percentiles-histogram.cashu_mint_task_duration_seconds=true
```

### 5.2 ObservabilityProperties Class

```java
@ConfigurationProperties(prefix = "cashu.observability")
@Data
public class ObservabilityProperties {
    private TasksProperties tasks = new TasksProperties();
    private HealthProperties health = new HealthProperties();

    @Data
    public static class TasksProperties {
        private boolean enabled = true;
    }

    @Data
    public static class HealthProperties {
        private GatewayHealthProperties gateway = new GatewayHealthProperties();
        private VaultHealthProperties vault = new VaultHealthProperties();
    }

    @Data
    public static class GatewayHealthProperties {
        private boolean enabled = true;
    }

    @Data
    public static class VaultHealthProperties {
        private boolean enabled = true;
    }
}
```

---

## 6. Docker Infrastructure

### 6.1 Prometheus Configuration

`docker/prometheus/prometheus.yml`:
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'cashu-mint'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['cashu-mint-rest-dev:7777']
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
        regex: '([^:]+):\d+'
        replacement: '${1}'

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
```

### 6.2 Grafana Datasource

`docker/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

### 6.3 Docker Compose Observability Stack

`docker/docker-compose.observability.yml`:
```yaml
services:
  prometheus:
    image: prom/prometheus:v2.47.0
    container_name: cashu-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--storage.tsdb.retention.time=15d'
      - '--web.enable-lifecycle'
    networks:
      - cashu
    restart: unless-stopped

  grafana:
    image: grafana/grafana:10.2.0
    container_name: cashu-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    networks:
      - cashu
    depends_on:
      - prometheus
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:

networks:
  cashu:
    external: true
```

---

## 7. Grafana Dashboards

### 7.1 Overview Dashboard Panels

**cashu-mint-overview.json** - Key panels:

1. **Request Rate** (Graph)
   - Query: `rate(cashu_mint_requests_total[5m])`
   - Group by: `endpoint`

2. **Request Latency P99** (Graph)
   - Query: `histogram_quantile(0.99, rate(cashu_mint_requests_duration_seconds_bucket[5m]))`

3. **Error Rate** (Graph)
   - Query: `rate(cashu_mint_requests_errors_total[5m])`

4. **Outstanding Sats** (Stat)
   - Query: `cashu_mint_sats_outstanding`

5. **Active Keysets** (Stat)
   - Query: `cashu_mint_keysets_active`

6. **Health Status** (Status Map)
   - Query: `up{job="cashu-mint"}`

### 7.2 Operations Dashboard Panels

**cashu-mint-operations.json** - Key panels:

1. **Proofs Issued/Spent Rate** (Graph)
   - Query: `rate(cashu_mint_proofs_issued_total[5m])`, `rate(cashu_mint_proofs_spent_total[5m])`

2. **Double-Spend Attempts** (Graph)
   - Query: `rate(cashu_mint_proofs_double_spend_total[5m])`

3. **Task Execution Time** (Heatmap)
   - Query: `rate(cashu_mint_task_duration_seconds_bucket[5m])`

4. **Task Success/Failure Rate** (Graph)
   - Query: `rate(cashu_mint_task_success_total[5m])`, `rate(cashu_mint_task_failure_total[5m])`

5. **Quote Funnel** (Sankey/Table)
   - Created vs Completed quotes

6. **Gateway Latency** (Graph)
   - Query: `histogram_quantile(0.95, rate(cashu_mint_gateway_request_duration_seconds_bucket[5m]))`

### 7.3 Business Dashboard Panels

**cashu-mint-business.json** - Key panels:

1. **Sats Issued Over Time** (Graph)
   - Query: `increase(cashu_mint_sats_issued_total[1h])`

2. **Sats Redeemed Over Time** (Graph)
   - Query: `increase(cashu_mint_sats_redeemed_total[1h])`

3. **Net Position** (Graph)
   - Query: `cashu_mint_sats_outstanding`

4. **Voucher Issuance** (Graph)
   - Query: `rate(cashu_mint_vouchers_issued_total[5m])`

5. **Voucher Fees Collected** (Stat)
   - Query: `cashu_mint_voucher_quote_fee_sats_total`

6. **Operations by Type** (Pie Chart)
   - Mint vs Melt vs Swap distribution

---

## 8. Integration with cashu-mint-rest

### 8.1 Add Dependency

Update `cashu-mint-rest/pom.xml`:
```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>cashu-mint-observability</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 8.2 Instrument Controllers

Inject metrics into `CashuController`:
```java
@RestController
@RequestMapping("/v1")
public class CashuController<T extends Secret> {

    private final MintMetrics mintMetrics;
    private final QuoteMetrics quoteMetrics;

    public CashuController(NUT06 nut06, MintLoadService mintLoadService,
                           SignatureVaultService signatureVaultService,
                           @Autowired(required = false) MintMetrics mintMetrics,
                           @Autowired(required = false) QuoteMetrics quoteMetrics) {
        this.nut06 = nut06;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
        this.mintMetrics = mintMetrics;
        this.quoteMetrics = quoteMetrics;
    }

    @PostMapping("/swap")
    public ResponseEntity<PostSwapResponse> swap(@RequestBody PostSwapRequest<T> request) {
        // ... existing logic ...
        PostSwapResponse response = NUT03.swap(mintId, request, mintLoadService, signatureVaultService);

        // Record metrics
        if (mintMetrics != null && response != null) {
            mintMetrics.recordSwap(request.getInputs().size(), response.getSignatures().size());
        }

        return ResponseEntity.ok(response);
    }
}
```

### 8.3 Update Docker Compose Dev

Extend `docker-compose.dev.yml` to include observability services:
```yaml
# Add to existing docker-compose.dev.yml or use extends
include:
  - path: cashu-mint-observability/docker/docker-compose.observability.yml
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

```java
// MintMetricsTest.java
@ExtendWith(MockitoExtension.class)
class MintMetricsTest {

    private SimpleMeterRegistry registry;
    private MintMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MintMetrics(registry);
    }

    @Test
    void recordProofIssued_incrementsCounter() {
        metrics.recordProofIssued("keyset1", 100);

        Counter counter = registry.find("cashu_mint_proofs_issued_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordProofSpent_decrementsOutstanding() {
        metrics.recordProofIssued("keyset1", 100);
        metrics.recordProofSpent("keyset1", 50);

        Gauge gauge = registry.find("cashu_mint_sats_outstanding").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(50.0);
    }
}
```

### 9.2 Integration Tests

```java
// ObservabilityIT.java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
class ObservabilityIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void prometheusEndpoint_exposesMetrics() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("cashu_mint_requests_total");
    }

    @Test
    void swapEndpoint_recordsMetrics() {
        // Perform swap
        // ...

        // Verify metrics recorded
        ResponseEntity<String> metrics = restTemplate.getForEntity(
            "/actuator/prometheus", String.class);
        assertThat(metrics.getBody()).contains("cashu_mint_proofs_issued_total");
    }
}
```

---

## 10. Implementation Phases

### Phase 1: Foundation (Days 1-2) ✅
- [x] Create `cashu-mint-observability` module structure
- [x] Add Maven dependencies and parent POM updates
- [x] Implement `ObservabilityAutoConfiguration`
- [x] Implement core `MintMetrics` class
- [x] Add Prometheus registry configuration
- [x] Expose `/actuator/prometheus` endpoint
- [x] Write unit tests for metrics classes

### Phase 2: Task Instrumentation (Days 2-3) ✅
- [x] Implement `TaskMetrics` class
- [x] Create `TaskTimingAspect` for AOP instrumentation
- [x] Instrument all task classes (see list in 3.4)
- [x] Add task success/failure counters
- [x] Write tests for task instrumentation

### Phase 3: Controller Metrics (Day 3) ✅
- [x] Implement `MetricsHandlerInterceptor`
- [x] Add request counting and timing
- [x] Handle endpoint normalization (prevent high cardinality)
- [x] Integrate interceptor with Spring MVC
- [x] Write integration tests

### Phase 4: Business Metrics (Day 4) ✅
- [x] Implement `QuoteMetrics`
- [x] Implement `VoucherMetrics`
- [x] Add sats issued/redeemed tracking
- [x] Add outstanding balance gauge
- [x] Integrate metrics into controllers

### Phase 5: Health & Infrastructure (Day 4) ✅
- [x] Implement `GatewayHealthIndicator`
- [x] Implement `VaultHealthIndicator`
- [x] Create Docker Prometheus configuration
- [x] Create Docker Grafana configuration
- [x] Create `docker-compose.observability.yml`

### Phase 6: Dashboards (Day 5) ✅
- [x] Create Overview dashboard JSON
- [x] Create Operations dashboard JSON
- [x] Create Business dashboard JSON
- [x] Configure Grafana provisioning
- [x] Document dashboard usage

### Phase 7: Documentation & Polish (Day 5) ✅
- [x] Write `metrics-reference.md`
- [x] Update CLAUDE.md with observability info
- [x] Create how-to guide for enabling observability
- [x] Final testing and cleanup

---

## 11. Privacy & Cardinality Considerations

### What NOT to Track
- Individual proof IDs or secrets
- User-identifiable information
- Full request/response payloads
- High-cardinality labels (quote IDs, transaction IDs)

### Label Guidelines
- Use bounded label sets (endpoint names, methods, status codes)
- Normalize variable path segments
- Limit unique time series per metric to < 1000
- Use keyset ID only when necessary (limited set)

---

## 12. Performance Impact

### Expected Overhead
- Metrics collection: < 0.5% CPU
- Memory for metrics: ~10-20 MB
- Scrape endpoint response: ~5-10 KB

### Mitigation Strategies
- Use sampling for high-frequency operations if needed
- Lazy metric registration
- Bounded label cardinality
- Histogram bucket optimization

---

## 13. Success Criteria

- [x] `/actuator/prometheus` returns all defined metrics
- [x] Grafana dashboards display correctly
- [x] Request latency P99 visible in dashboard
- [x] Task execution times tracked
- [x] Business metrics (sats issued/redeemed) accurate
- [x] Health indicators reflect actual service state
- [x] Performance overhead < 1%
- [x] All unit and integration tests pass (120 tests)
- [x] Documentation complete

---

## 14. Future Enhancements (Phase 2)

### 14.1 OpenTelemetry Distributed Tracing

Enable end-to-end request tracing across the mint, vault, and gateway services.

**Dependencies to add:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

**Configuration:**
```properties
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://tempo:4318/v1/traces
```

**Implementation tasks:**
- Add trace context propagation to gateway client calls
- Add trace context propagation to vault client calls
- Instrument custom spans for critical operations (signing, verification)
- Add Tempo or Jaeger to docker-compose.observability.yml

**Benefits:**
- Visualize request flow across services
- Identify latency bottlenecks in distributed calls
- Debug complex multi-service failures

---

### 14.2 Prometheus Alerting Rules

Create alerting rules for critical operational thresholds.

**File: `docker/prometheus/alerts.yml`**
```yaml
groups:
  - name: cashu-mint-alerts
    rules:
      # High error rate alert
      - alert: HighErrorRate
        expr: |
          sum(rate(cashu_mint_requests_total{status=~"5xx"}[5m]))
          / sum(rate(cashu_mint_requests_total[5m])) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High error rate detected"
          description: "Error rate is {{ $value | humanizePercentage }} over the last 5 minutes"

      # High latency alert
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99, sum by (le) (rate(cashu_mint_requests_duration_seconds_bucket[5m]))) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High request latency"
          description: "P99 latency is {{ $value | humanizeDuration }}"

      # Double-spend spike alert
      - alert: DoubleSpendSpike
        expr: rate(cashu_mint_proofs_double_spend_total[5m]) > 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Double-spend attempts detected"
          description: "{{ $value | humanize }} double-spend attempts per second"

      # Service down alert
      - alert: MintServiceDown
        expr: up{job="cashu-mint"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Cashu Mint service is down"

      # High outstanding liability alert
      - alert: HighOutstandingLiability
        expr: cashu_mint_sats_outstanding > 10000000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High outstanding liability"
          description: "Outstanding sats: {{ $value | humanize }}"

      # Quote backlog alert
      - alert: QuoteBacklog
        expr: cashu_mint_quotes_active > 100
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Large quote backlog"
          description: "{{ $value }} active quotes pending"

      # Task failure spike
      - alert: TaskFailureSpike
        expr: |
          sum(rate(cashu_mint_task_failure_total[5m]))
          / (sum(rate(cashu_mint_task_success_total[5m])) + sum(rate(cashu_mint_task_failure_total[5m]))) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High task failure rate"
          description: "Task failure rate is {{ $value | humanizePercentage }}"
```

**Alertmanager integration:**
```yaml
# docker/alertmanager/alertmanager.yml
global:
  resolve_timeout: 5m

route:
  group_by: ['alertname', 'severity']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 1h
  receiver: 'slack-notifications'

receivers:
  - name: 'slack-notifications'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#cashu-alerts'
        send_resolved: true
```

---

### 14.3 Grafana Alerting

Create dashboard-based alerts with notification channels.

**Recommended alerts:**

| Alert | Condition | Severity |
|-------|-----------|----------|
| Service Down | `up{job="cashu-mint"} == 0` | Critical |
| High Error Rate | `error_rate > 5%` for 5m | Critical |
| High P99 Latency | `p99 > 2s` for 5m | Warning |
| Double-Spend Detected | `rate > 0` for 1m | Critical |
| Large Quote Backlog | `active_quotes > 100` for 5m | Warning |
| Voucher Rejection Spike | `rejection_rate > 10%` for 5m | Warning |

**Notification channels to configure:**
- Slack webhook
- PagerDuty integration
- Email notifications
- Telegram bot (optional)

---

### 14.4 Log Correlation with Loki

Integrate structured logging with trace correlation.

**Dependencies:**
```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

**Logback configuration (`logback-spring.xml`):**
```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

**Loki Docker configuration:**
```yaml
# Add to docker-compose.observability.yml
loki:
  image: grafana/loki:2.9.0
  container_name: cashu-loki
  ports:
    - "3100:3100"
  volumes:
    - loki-data:/loki
  command: -config.file=/etc/loki/local-config.yaml
  networks:
    - cashu

promtail:
  image: grafana/promtail:2.9.0
  container_name: cashu-promtail
  volumes:
    - /var/log:/var/log:ro
    - ./promtail/promtail-config.yml:/etc/promtail/config.yml:ro
  command: -config.file=/etc/promtail/config.yml
  networks:
    - cashu
```

**Benefits:**
- Correlate logs with traces using traceId
- Query logs from Grafana alongside metrics
- Full-text search across all services

---

### 14.5 SLO/SLI Dashboards

Create Service Level Objective dashboards for reliability tracking.

**Key SLIs to track:**

| SLI | Target | Measurement |
|-----|--------|-------------|
| Availability | 99.9% | `up{job="cashu-mint"}` |
| Request Success Rate | 99.5% | `1 - (5xx / total)` |
| P99 Latency | < 500ms | `histogram_quantile(0.99, ...)` |
| Quote Completion Rate | 95% | `completed / created` |

**Error budget calculation:**
```promql
# Monthly error budget remaining
1 - (
  sum(increase(cashu_mint_requests_total{status=~"5xx"}[30d]))
  / sum(increase(cashu_mint_requests_total[30d]))
) / 0.001  # 99.9% SLO = 0.1% error budget
```

---

### 14.6 Gateway-Specific Metrics

Add detailed metrics for Lightning gateway operations.

**New metrics to implement:**

| Metric | Type | Description |
|--------|------|-------------|
| `cashu_mint_gateway_invoice_created_total` | Counter | Invoices created |
| `cashu_mint_gateway_invoice_paid_total` | Counter | Invoices paid |
| `cashu_mint_gateway_payment_sent_total` | Counter | Outgoing payments |
| `cashu_mint_gateway_payment_failed_total` | Counter | Failed payments |
| `cashu_mint_gateway_latency_seconds` | Timer | Gateway call latency |
| `cashu_mint_gateway_balance_sats` | Gauge | Gateway balance |

**Implementation:**
Create `GatewayMetrics.java` class and instrument gateway adapter calls.

---

### 14.7 Cost Analysis Dashboard

Track operational costs and revenue metrics.

**Metrics to add:**

| Metric | Description |
|--------|-------------|
| `cashu_mint_fees_collected_total` | Total fees collected (sats) |
| `cashu_mint_lightning_fees_paid_total` | Lightning routing fees paid |
| `cashu_mint_net_revenue_sats` | Fees collected - fees paid |

**Dashboard panels:**
- Daily/weekly/monthly fee revenue
- Fee revenue by operation type
- Lightning fee costs
- Net margin calculation

---

### 14.8 Implementation Priority

| Enhancement | Priority | Effort | Impact |
|-------------|----------|--------|--------|
| Prometheus Alerting | High | Low | High |
| Grafana Alerts | High | Low | High |
| Log Correlation | Medium | Medium | Medium |
| OpenTelemetry Tracing | Medium | Medium | High |
| SLO Dashboards | Medium | Low | Medium |
| Gateway Metrics | Low | Medium | Medium |
| Cost Analysis | Low | Low | Low |

---

**Document Version**: 1.1
**Author**: Claude Code
**Last Updated**: 2025-11-29
