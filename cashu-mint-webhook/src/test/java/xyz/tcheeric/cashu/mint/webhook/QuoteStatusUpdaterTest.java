package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuoteStatusUpdater.
 */
class QuoteStatusUpdaterTest {

    private QuoteStatusUpdater updater;

    @BeforeEach
    void setUp() {
        // Create with standard config - 1 hour quote TTL, 24 hour idempotency TTL
        // 10MB max weight, 100k max idempotency keys, no MeterRegistry
        updater = new QuoteStatusUpdater(
                Duration.ofHours(1),
                Duration.ofHours(24),
                10_485_760L,  // 10MB max weight
                100_000,
                null  // No MeterRegistry for basic tests
        );
    }

    @Test
    void markAsPaid_shouldStoreNotification() {
        // Given: a payment notification
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");

        // When: marking as paid
        boolean result = updater.markAsPaid(notification);

        // Then: notification is stored
        assertTrue(result);
        assertTrue(updater.isPaid("quote123"));
        assertEquals(1, updater.getCacheSize());
    }

    @Test
    void markAsPaid_shouldBeIdempotent() {
        // Given: a payment notification
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");

        // When: marking as paid twice
        boolean first = updater.markAsPaid(notification);
        boolean second = updater.markAsPaid(notification);

        // Then: duplicate is detected
        assertTrue(first);
        assertFalse(second);
        assertEquals(1, updater.getCacheSize());
        assertEquals(1, updater.getProcessedCount());
    }

    @Test
    void isPaid_shouldReturnFalseForUnknownQuote() {
        // When/Then: unknown quote returns false
        assertFalse(updater.isPaid("unknown-quote"));
    }

    @Test
    void getPaymentDetails_shouldReturnNotification() {
        // Given: a stored notification
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When: retrieving details
        Optional<PaymentNotification> result = updater.getPaymentDetails("quote123");

        // Then: details are returned
        assertTrue(result.isPresent());
        assertEquals("quote123", result.get().getQuoteId());
        assertEquals(1000, result.get().getAmount());
    }

    @Test
    void getPaymentDetails_shouldReturnEmptyForUnknown() {
        // When: requesting unknown quote
        Optional<PaymentNotification> result = updater.getPaymentDetails("unknown");

        // Then: empty optional
        assertTrue(result.isEmpty());
    }

    @Test
    void getPreimage_shouldReturnPreimage() {
        // Given: a notification with preimage
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When: retrieving preimage
        Optional<String> result = updater.getPreimage("quote123");

        // Then: preimage is returned
        assertTrue(result.isPresent());
        assertEquals("preimage456", result.get());
    }

    @Test
    void consumeQuote_shouldRemoveQuote() {
        // Given: a paid quote
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);
        assertTrue(updater.isPaid("quote123"));

        // When: consuming the quote
        PaymentNotification removed = updater.consumeQuote("quote123");

        // Then: quote is removed
        assertNotNull(removed);
        assertEquals("quote123", removed.getQuoteId());
        assertFalse(updater.isPaid("quote123"));
        assertEquals(0, updater.getCacheSize());
    }

    @Test
    void consumeQuote_shouldReturnNullForUnknown() {
        // When: consuming unknown quote
        PaymentNotification result = updater.consumeQuote("unknown");

        // Then: null returned
        assertNull(result);
    }

    @Test
    void markConsumed_shouldCallConsumeQuote() {
        // Given: a paid quote
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When: marking as consumed
        updater.markConsumed("quote123");

        // Then: quote is removed
        assertFalse(updater.isPaid("quote123"));
    }

    @Test
    void clear_shouldRemoveAllData() {
        // Given: multiple stored quotes
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "bolt11", 200, "p2"));
        assertEquals(2, updater.getCacheSize());

        // When: clearing
        updater.clear();

        // Then: all data removed
        assertEquals(0, updater.getCacheSize());
        assertEquals(0, updater.getProcessedCount());
        assertFalse(updater.isPaid("quote1"));
        assertFalse(updater.isPaid("quote2"));
    }

    @Test
    void shouldHandleMultipleQuotes() {
        // Given/When: storing multiple quotes
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "cash", 200, null));
        updater.markAsPaid(createNotification("quote3", "bolt11", 300, "p3"));

        // Then: all quotes stored
        assertEquals(3, updater.getCacheSize());
        assertTrue(updater.isPaid("quote1"));
        assertTrue(updater.isPaid("quote2"));
        assertTrue(updater.isPaid("quote3"));
    }

    @Test
    void weightBasedEviction_shouldEvictWhenMaxWeightExceeded() {
        // Given: a cache with very small max weight (2KB)
        QuoteStatusUpdater smallCache = new QuoteStatusUpdater(
                Duration.ofHours(1),
                Duration.ofHours(24),
                2048L,  // 2KB max weight - very small
                100,
                null
        );

        // Create notifications with large preimages to exceed weight
        String largePreimage = "x".repeat(500);  // ~1000 bytes per notification

        // When: adding entries that exceed max weight
        smallCache.markAsPaid(createNotification("quote1", "bolt11", 100, largePreimage));
        smallCache.markAsPaid(createNotification("quote2", "bolt11", 200, largePreimage));
        smallCache.markAsPaid(createNotification("quote3", "bolt11", 300, largePreimage));
        smallCache.markAsPaid(createNotification("quote4", "bolt11", 400, largePreimage));

        // Force cleanup to trigger eviction
        smallCache.cleanUp();

        // Then: some entries should be evicted (cache won't hold all 4)
        int cacheSize = smallCache.getCacheSize();
        assertTrue(cacheSize < 4, "Expected some entries to be evicted, but cache size is " + cacheSize);
        assertTrue(smallCache.getPaidQuotesEvictionCount() > 0, "Expected evictions to occur");
    }

    @Test
    void concurrentAccess_shouldHandleParallelWrites() throws InterruptedException {
        // Given: a thread pool for concurrent access
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * operationsPerThread);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        // When: multiple threads write concurrently
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            for (int i = 0; i < operationsPerThread; i++) {
                final int opId = i;
                executor.submit(() -> {
                    try {
                        String quoteId = "quote-" + threadId + "-" + opId;
                        PaymentNotification notification = createNotification(
                                quoteId, "bolt11", 100, "preimage-" + quoteId);
                        if (updater.markAsPaid(notification)) {
                            successCount.incrementAndGet();
                        } else {
                            duplicateCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        // Wait for completion
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: all operations complete successfully
        assertTrue(completed, "Operations did not complete in time");
        assertEquals(threadCount * operationsPerThread, successCount.get(),
                "All unique quotes should be marked as paid");
        assertEquals(0, duplicateCount.get(), "No duplicates expected with unique quote IDs");
        assertEquals(threadCount * operationsPerThread, updater.getCacheSize());
    }

    @Test
    void concurrentAccess_shouldDetectDuplicatesAcrossThreads() throws InterruptedException {
        // Given: multiple threads trying to mark the same quote
        int threadCount = 10;
        String sharedQuoteId = "shared-quote";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        // When: all threads try to mark the same quote simultaneously
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();  // Wait for signal to start
                    PaymentNotification notification = createNotification(
                            sharedQuoteId, "bolt11", 100, "preimage-" + threadId);
                    if (updater.markAsPaid(notification)) {
                        successCount.incrementAndGet();
                    } else {
                        duplicateCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();  // Start all threads
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: exactly one thread succeeds, others detect duplicate
        assertTrue(completed, "Operations did not complete in time");
        assertEquals(1, successCount.get(), "Exactly one thread should succeed");
        assertEquals(threadCount - 1, duplicateCount.get(), "Other threads should detect duplicate");
        assertEquals(1, updater.getCacheSize());
    }

    @Test
    void cacheStats_shouldTrackHitRateAfterAccess() {
        // Given: some stored quotes
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "bolt11", 200, "p2"));

        // When: performing hits and misses
        updater.isPaid("quote1");  // hit
        updater.isPaid("quote1");  // hit
        updater.isPaid("quote2");  // hit
        updater.isPaid("nonexistent");  // miss
        updater.isPaid("also-missing");  // miss

        // Then: hit rate should be approximately 60% (3 hits / 5 accesses)
        double hitRate = updater.getPaidQuotesHitRate();
        assertTrue(hitRate > 0.5 && hitRate < 0.7,
                "Expected hit rate around 0.6, but was " + hitRate);
    }

    @Test
    void cleanUp_shouldForceExpirationProcessing() {
        // Given: a cache with entries
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "bolt11", 200, "p2"));

        // When: calling cleanUp
        updater.cleanUp();

        // Then: entries still exist (TTL not expired)
        assertEquals(2, updater.getCacheSize());
        assertTrue(updater.isPaid("quote1"));
        assertTrue(updater.isPaid("quote2"));
    }

    @Test
    void shouldHandleNullPreimage() {
        // Given: a notification without preimage (e.g., cash payment)
        PaymentNotification notification = createNotification("cash-quote", "cash", 500, null);

        // When: marking as paid
        boolean result = updater.markAsPaid(notification);

        // Then: succeeds and returns empty preimage
        assertTrue(result);
        assertTrue(updater.isPaid("cash-quote"));
        assertTrue(updater.getPreimage("cash-quote").isEmpty());
    }

    @Test
    void weigher_shouldHandleVariableSizeNotifications() {
        // Given: notifications of varying sizes
        PaymentNotification small = PaymentNotification.builder()
                .quoteId("small")
                .paymentMethod("bolt11")
                .amount(100)
                .paidAt(Instant.now())
                .build();

        PaymentNotification large = PaymentNotification.builder()
                .quoteId("large")
                .paymentMethod("bolt11")
                .amount(100)
                .preimage("x".repeat(1000))  // Large preimage
                .receiptId("receipt-" + "y".repeat(500))
                .paidAt(Instant.now())
                .build();

        // When: both are stored
        updater.markAsPaid(small);
        updater.markAsPaid(large);

        // Then: both are accessible (weigher doesn't reject, just affects eviction order)
        assertTrue(updater.isPaid("small"));
        assertTrue(updater.isPaid("large"));
    }

    @Test
    void idempotencyKeys_shouldPersistAcrossQuoteEviction() {
        // Given: a cache with very small weight to force eviction
        QuoteStatusUpdater smallCache = new QuoteStatusUpdater(
                Duration.ofHours(1),
                Duration.ofHours(24),
                1024L,  // 1KB - very small
                1000,   // Large idempotency cache
                null
        );

        String largePreimage = "x".repeat(300);
        PaymentNotification notification = createNotification("quote1", "bolt11", 100, largePreimage);

        // Mark as paid
        assertTrue(smallCache.markAsPaid(notification));

        // Add more entries to force eviction
        for (int i = 2; i <= 10; i++) {
            smallCache.markAsPaid(createNotification("quote" + i, "bolt11", 100, largePreimage));
        }
        smallCache.cleanUp();

        // When: trying to mark the same quote again (even if evicted from quote cache)
        PaymentNotification duplicate = createNotification("quote1", "bolt11", 100, largePreimage);
        boolean result = smallCache.markAsPaid(duplicate);

        // Then: idempotency check still works (idempotency cache is separate)
        assertFalse(result, "Should detect duplicate via idempotency key even after quote eviction");
    }

    private PaymentNotification createNotification(String quoteId, String method, int amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod(method)
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }
}
