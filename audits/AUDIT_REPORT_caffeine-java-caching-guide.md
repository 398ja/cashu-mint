# Audit Report

**Source:** [Master Caffeine: Java's Ultimate Caching Guide](https://readmedium.com/master-caffeine-javas-ultimate-caching-guide-17fb72ab0264)
**Date:** 2026-02-02
**Codebase:** cashu-mint (v0.12.0)
**Status:** REMEDIATED

## Executive Summary

- **Total Guidelines Evaluated:** 8
- **Applicable to Codebase:** 6
- **Original Findings:** 4 (0 critical, 2 high, 1 medium, 1 low)
- **Remediated:** 4
- **Compliance Score:** 100% (6 compliant / 6 applicable)

### Remediation Summary

| Finding | Status | Commit |
|---------|--------|--------|
| GUIDE-001: Weigher-Based Eviction | FIXED | See QuoteStatusUpdater.java |
| GUIDE-005: Observability Metrics | FIXED | See QuoteStatusUpdater.java |
| GUIDE-008: VoucherQuoteRegistry TTL | FIXED | See VoucherQuoteRegistry.java |
| GUIDE-007: Eviction Testing | FIXED | See QuoteStatusUpdaterTest.java |

## Codebase Capabilities Detected

| Capability | Status | Key Files |
|------------|--------|-----------|
| Caffeine Caching | Present | `cashu-mint-webhook/.../QuoteStatusUpdater.java` |
| Spring Cache Abstraction | Not Present | - |
| ConcurrentHashMap In-Memory Storage | Present | `VoucherQuoteRegistry.java`, `SubscriptionManager.java` |
| Async Executors | Present | `AsyncConfig.java`, `SubscriptionManager.java` |
| Metrics/Observability | Present | `cashu-mint-observability/...` |

## Findings

### High Severity

#### [GUIDE-001] Weigher-Based Eviction for Memory Efficiency

**Status:** ~~NON-COMPLIANT~~ **FIXED**
**Guideline:** Use weighers to control cache size based on object memory footprint rather than entry count. This prevents heap pressure when large objects consume disproportionate resources.
**Source:** Caffeine Caching Guide - Weigher-Based Eviction

**Locations:**
- `cashu-mint-webhook/src/main/java/xyz/tcheeric/cashu/mint/webhook/QuoteStatusUpdater.java:55-65`

**Current Code:**
```java
this.paidQuotes = Caffeine.newBuilder()
        .expireAfterWrite(quoteTtl)
        .maximumSize(maxQuotes)  // Count-based only
        .evictionListener((key, value, cause) ->
                log.debug("Quote evicted from cache: key={}, cause={}", key, cause))
        .build();

this.processedNotifications = Caffeine.newBuilder()
        .expireAfterWrite(idempotencyTtl)
        .maximumSize(maxIdempotencyKeys)  // Count-based only
        .build();
```

**Analysis:**
The caches use only `maximumSize()` without considering actual memory costs. While `PaymentNotification` objects are relatively uniform in size, the idempotency cache stores `Boolean` values which are lightweight. However, for the `paidQuotes` cache, `PaymentNotification` objects could vary significantly in size if the `preimage` field varies in length or if additional metadata is added in the future.

**Recommended Fix:**
```java
// For paidQuotes cache with heterogeneous object sizes
this.paidQuotes = Caffeine.newBuilder()
        .expireAfterWrite(quoteTtl)
        .maximumWeight(maxQuotes * ESTIMATED_NOTIFICATION_SIZE)  // e.g., 10_000 * 256 bytes
        .weigher((String key, PaymentNotification value) ->
                estimateSize(key, value))
        .evictionListener((key, value, cause) ->
                log.debug("Quote evicted from cache: key={}, cause={}", key, cause))
        .build();

private int estimateSize(String key, PaymentNotification notification) {
    int size = 40;  // Object overhead
    size += key.length() * 2;  // String key
    size += notification.getQuoteId().length() * 2;
    size += notification.getPaymentMethod().length() * 2;
    if (notification.getPreimage() != null) {
        size += notification.getPreimage().length() * 2;
    }
    return size;
}
```

**Note:** For the `processedNotifications` cache storing `Boolean` values, count-based sizing is acceptable since all entries have uniform size.

---

#### [GUIDE-005] Observability: Metrics and Tracing

**Status:** ~~NON-COMPLIANT~~ **FIXED**
**Guideline:** Enable `recordStats()` and export hit rate, miss count, load time, and eviction count to identify performance regressions and correlate cache behavior with downstream dependencies.
**Source:** Caffeine Caching Guide - Observability Section

**Locations:**
- `cashu-mint-webhook/src/main/java/xyz/tcheeric/cashu/mint/webhook/QuoteStatusUpdater.java:55-65`

**Current Code:**
```java
this.paidQuotes = Caffeine.newBuilder()
        .expireAfterWrite(quoteTtl)
        .maximumSize(maxQuotes)
        // Missing: .recordStats()
        .build();
```

**Analysis:**
The caches do not enable `recordStats()`. While the observability module (`cashu-mint-observability`) provides extensive metrics for locks, quotes, and tasks, the Caffeine cache statistics are not exported. This makes it impossible to monitor:
- Cache hit/miss rates
- Eviction frequency and causes
- Average load time (if using loading cache)
- Entry counts over time

**Recommended Fix:**
```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

@Service
public class QuoteStatusUpdater implements PaymentStatusChecker {

    private final Cache<String, PaymentNotification> paidQuotes;
    private final Cache<String, Boolean> processedNotifications;

    public QuoteStatusUpdater(
            @Value("${webhook.cache.quote-ttl:1h}") Duration quoteTtl,
            @Value("${webhook.cache.idempotency-ttl:24h}") Duration idempotencyTtl,
            @Value("${webhook.cache.max-quotes:10000}") int maxQuotes,
            @Value("${webhook.cache.max-idempotency-keys:100000}") int maxIdempotencyKeys,
            MeterRegistry meterRegistry) {  // Inject MeterRegistry

        this.paidQuotes = Caffeine.newBuilder()
                .expireAfterWrite(quoteTtl)
                .maximumSize(maxQuotes)
                .recordStats()  // Enable stats
                .evictionListener((key, value, cause) ->
                        log.debug("Quote evicted from cache: key={}, cause={}", key, cause))
                .build();

        this.processedNotifications = Caffeine.newBuilder()
                .expireAfterWrite(idempotencyTtl)
                .maximumSize(maxIdempotencyKeys)
                .recordStats()  // Enable stats
                .build();

        // Register with Micrometer
        CaffeineCacheMetrics.monitor(meterRegistry, paidQuotes, "webhook.paid_quotes");
        CaffeineCacheMetrics.monitor(meterRegistry, processedNotifications, "webhook.processed_notifications");

        log.info("QuoteStatusUpdater initialized with metrics");
    }
}
```

This will expose metrics like:
- `cache.gets{cache=webhook.paid_quotes,result=hit|miss}`
- `cache.evictions{cache=webhook.paid_quotes}`
- `cache.size{cache=webhook.paid_quotes}`

---

### Medium Severity

#### [GUIDE-008] Unbounded In-Memory Storage Without TTL

**Status:** ~~NEEDS REVIEW~~ **FIXED**
**Guideline:** Combine time-based expiration with size limits to provide defense-in-depth against unbounded memory growth and stale data.
**Source:** Caffeine Caching Guide - Multi-Dimensional Eviction Strategy

**Locations:**
- `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/VoucherQuoteRegistry.java:27`

**Current Code:**
```java
public final class VoucherQuoteRegistry {
    private static final Map<String, Long> faceValues = new ConcurrentHashMap<>();

    public static void storeFaceValue(String quoteId, long faceValue) {
        faceValues.put(quoteId, faceValue);  // No size limit, no TTL
    }
    // ...
}
```

**Analysis:**
The `VoucherQuoteRegistry` uses a `ConcurrentHashMap` with no eviction mechanism:
- No maximum size limit
- No TTL-based expiration
- Relies entirely on manual `removeFaceValue()` calls for cleanup

If `removeFaceValue()` is not called (e.g., due to an exception during minting), entries accumulate indefinitely. The code comments acknowledge this limitation: *"For production multi-instance deployments, consider using a distributed cache (Redis) or database storage."*

**Risk Assessment:**
- **Low immediate risk**: Voucher minting is likely a low-volume operation
- **Memory leak potential**: Uncleaned entries persist until JVM restart
- **Monitoring gap**: No way to track accumulated entries in production

**Recommended Fix:**
```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public final class VoucherQuoteRegistry {
    // Replace ConcurrentHashMap with Caffeine cache
    private static final Cache<String, Long> faceValues = Caffeine.newBuilder()
            .maximumSize(10_000)  // Reasonable upper bound
            .expireAfterWrite(Duration.ofHours(24))  // Same as quote expiry
            .evictionListener((key, value, cause) ->
                    log.warn("Voucher face value evicted: quoteId={}, cause={}", key, cause))
            .build();

    public static void storeFaceValue(String quoteId, long faceValue) {
        faceValues.put(quoteId, faceValue);
    }

    public static Long getFaceValue(String quoteId) {
        return faceValues.getIfPresent(quoteId);
    }
    // ...
}
```

---

### Low Severity

#### [GUIDE-007] Comprehensive Testing of Eviction Policies

**Status:** ~~PARTIAL~~ **FIXED**
**Guideline:** Test eviction policies under load; verify refresh and async behaviors; simulate slow/failing sources.
**Source:** Caffeine Caching Guide - Testing Section

**Locations:**
- `cashu-mint-webhook/src/test/java/xyz/tcheeric/cashu/mint/webhook/QuoteStatusUpdaterTest.java`

**Current Code:**
```java
@BeforeEach
void setUp() {
    updater = new QuoteStatusUpdater(
            Duration.ofHours(1),  // Standard TTL, not short for testing eviction
            Duration.ofHours(24),
            10000,
            100000
    );
}
```

**Analysis:**
The existing tests cover:
- Basic put/get operations
- Idempotency behavior
- Manual invalidation via `consumeQuote()`
- Cache clearing

**Missing test coverage:**
- TTL-based eviction behavior (would require waiting or Caffeine's testing utilities)
- Size-based eviction when `maximumSize` is exceeded
- Concurrent access patterns
- Eviction listener callback verification

**Recommended Additional Tests:**
```java
@Test
void testSizeBasedEviction() {
    // Create cache with small size limit
    QuoteStatusUpdater smallCache = new QuoteStatusUpdater(
            Duration.ofHours(1),
            Duration.ofHours(24),
            2,  // Very small max size
            100
    );

    smallCache.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
    smallCache.markAsPaid(createNotification("quote2", "bolt11", 200, "p2"));
    smallCache.markAsPaid(createNotification("quote3", "bolt11", 300, "p3"));  // Should evict oldest

    // Caffeine eviction is asynchronous - need to allow time
    smallCache.cleanUp();  // Force cleanup

    assertFalse(smallCache.isPaid("quote1"), "Oldest entry should be evicted");
    assertTrue(smallCache.isPaid("quote2") || smallCache.isPaid("quote3"));
}

@Test
void testConcurrentAccess() throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch latch = new CountDownLatch(100);

    for (int i = 0; i < 100; i++) {
        final int idx = i;
        executor.submit(() -> {
            try {
                updater.markAsPaid(createNotification("quote" + idx, "bolt11", 100, "p" + idx));
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(5, TimeUnit.SECONDS);
    assertEquals(100, updater.getCacheSize());
    executor.shutdown();
}
```

---

## Compliant Areas

### [GUIDE-003] Spring Cache Abstraction Integration

**Status:** NOT APPLICABLE
The codebase does not use Spring's `@Cacheable`, `@CacheEvict`, or `@CachePut` annotations. Instead, it uses Caffeine directly for fine-grained control. This is appropriate for the webhook module's specific caching needs where programmatic cache management provides better control over idempotency keys and quote lifecycle.

### [GUIDE-002] Refresh Strategies for Non-Blocking Freshness

**Status:** COMPLIANT (Not Needed)
The `QuoteStatusUpdater` cache is write-through (data is pushed via webhooks) rather than read-through (data is fetched on access). The `refreshAfterWrite()` pattern is designed for caches that load data from external sources. Since payment notifications arrive via webhooks, there's no need for background refresh - data is fresh by design.

### [GUIDE-004] Custom Executor Control for Concurrency

**Status:** COMPLIANT
While the Caffeine caches don't explicitly configure a custom executor (they use simple `Cache` rather than `AsyncLoadingCache`), the codebase demonstrates good executor management elsewhere:

**File:** `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/config/AsyncConfig.java:36-38`
```java
@Bean
public TaskExecutor applicationTaskExecutor() {
    return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
}
```

**File:** `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/service/SubscriptionManager.java:290`
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // Parallel vault queries
}
```

The project consistently uses Virtual Threads (Java 21) for async operations, which is an excellent modern pattern.

### [GUIDE-006] Complex Data Loading Patterns

**Status:** NOT APPLICABLE
The caches don't require batch loading or result combining. The use cases are simple key-value storage for:
1. Quote ID to payment notification mapping
2. Idempotency key tracking

---

## Implementation Plan

~~Ordered list of changes to achieve full compliance, prioritized by severity and effort.~~

**All items completed on 2026-02-02.**

### Phase 1: High Priority (Observability) - COMPLETED

| # | Task | Files | Status |
|---|------|-------|--------|
| 1 | Add `recordStats()` to both Caffeine caches in QuoteStatusUpdater | `QuoteStatusUpdater.java` | DONE |
| 2 | Inject `MeterRegistry` and register caches with Micrometer | `QuoteStatusUpdater.java` | DONE |
| 3 | Add Micrometer dependency to cashu-mint-webhook pom | `cashu-mint-webhook/pom.xml` | DONE |
| 4 | Add cache metrics to Grafana dashboard | N/A | DEFERRED (metrics auto-exposed via Micrometer) |

### Phase 2: Medium Priority (VoucherQuoteRegistry) - COMPLETED

| # | Task | Files | Status |
|---|------|-------|--------|
| 5 | Replace ConcurrentHashMap with Caffeine cache in VoucherQuoteRegistry | `VoucherQuoteRegistry.java` | DONE |
| 6 | Add size limit and TTL (24h, 10k entries) | `VoucherQuoteRegistry.java` | DONE |
| 7 | Add eviction logging for debugging stale voucher quotes | `VoucherQuoteRegistry.java` | DONE |

### Phase 3: Medium Priority (Weigher-Based Eviction) - COMPLETED

| # | Task | Files | Status |
|---|------|-------|--------|
| 8 | Add weigher function for PaymentNotification size estimation | `QuoteStatusUpdater.java` | DONE |
| 9 | Replace `maximumSize` with `maximumWeight` for paidQuotes cache | `QuoteStatusUpdater.java` | DONE |
| 10 | Add configuration property for max cache weight in bytes | `QuoteStatusUpdater.java` | DONE (10MB default) |

### Phase 4: Low Priority (Testing) - COMPLETED

| # | Task | Files | Status |
|---|------|-------|--------|
| 11 | Add size-based eviction test with small cache | `QuoteStatusUpdaterTest.java` | DONE |
| 12 | Add concurrent access stress test | `QuoteStatusUpdaterTest.java` | DONE |
| 13 | Add eviction listener verification test | `QuoteStatusUpdaterTest.java` | DONE |
| 14 | Add VoucherQuoteRegistry Caffeine tests | `VoucherQuoteRegistryTest.java` | DONE |

---

## Guidelines Not Applicable

| Guideline | Reason |
|-----------|--------|
| GUIDE-002: Refresh Strategies | Write-through cache pattern; data arrives via webhooks, not fetched |
| GUIDE-003: Spring Cache Abstraction | Programmatic cache management is more appropriate for this use case |
| GUIDE-006: Complex Data Loading | Simple key-value use case; no batch loading required |

---

*Generated by `/audit` skill on 2026-02-02*
*Source: [Master Caffeine: Java's Ultimate Caching Guide](https://readmedium.com/master-caffeine-javas-ultimate-caching-guide-17fb72ab0264)*
