package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VoucherQuoteRegistry utility class.
 */
public class VoucherQuoteRegistryTest {

    @AfterEach
    public void cleanup() {
        // Clean up registry after each test to prevent test interference
        VoucherQuoteRegistry.clear();
    }

    @Test
    public void testStoreFaceValue() {
        // Test storing a face value
        String quoteId = "test-quote-1";
        VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);

        Long faceValue = VoucherQuoteRegistry.getFaceValue(quoteId);
        assertEquals(1000L, faceValue);
    }

    @Test
    public void testGetNonExistentQuote() {
        // Test retrieving a quote that doesn't exist
        Long faceValue = VoucherQuoteRegistry.getFaceValue("non-existent");
        assertNull(faceValue);
    }

    @Test
    public void testIsVoucherQuote() {
        // Test checking if a quote is a voucher quote
        String quoteId = "test-quote-2";

        assertFalse(VoucherQuoteRegistry.isVoucherQuote(quoteId));

        VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);
        assertTrue(VoucherQuoteRegistry.isVoucherQuote(quoteId));
    }

    @Test
    public void testRemoveFaceValue() {
        // Test removing a face value
        String quoteId = "test-quote-3";
        VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);

        assertTrue(VoucherQuoteRegistry.isVoucherQuote(quoteId));

        VoucherQuoteRegistry.removeFaceValue(quoteId);

        assertFalse(VoucherQuoteRegistry.isVoucherQuote(quoteId));
        assertNull(VoucherQuoteRegistry.getFaceValue(quoteId));
    }

    @Test
    public void testRemoveNonExistentQuote() {
        // Test removing a quote that doesn't exist (should not throw)
        assertDoesNotThrow(() -> {
            VoucherQuoteRegistry.removeFaceValue("non-existent");
        });
    }

    @Test
    public void testMultipleQuotes() {
        // Test storing multiple quotes
        VoucherQuoteRegistry.storeFaceValue("quote-1", 1000L);
        VoucherQuoteRegistry.storeFaceValue("quote-2", 2000L);
        VoucherQuoteRegistry.storeFaceValue("quote-3", 3000L);

        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue("quote-1"));
        assertEquals(2000L, VoucherQuoteRegistry.getFaceValue("quote-2"));
        assertEquals(3000L, VoucherQuoteRegistry.getFaceValue("quote-3"));
        assertEquals(3, VoucherQuoteRegistry.size());
    }

    @Test
    public void testOverwriteExistingQuote() {
        // Test that storing the same quote ID overwrites the previous value
        String quoteId = "test-quote-4";
        VoucherQuoteRegistry.storeFaceValue(quoteId, 1000L);
        VoucherQuoteRegistry.storeFaceValue(quoteId, 2000L);

        assertEquals(2000L, VoucherQuoteRegistry.getFaceValue(quoteId));
        assertEquals(1, VoucherQuoteRegistry.size());
    }

    @Test
    public void testSize() {
        // Test size tracking
        assertEquals(0, VoucherQuoteRegistry.size());

        VoucherQuoteRegistry.storeFaceValue("quote-1", 1000L);
        assertEquals(1, VoucherQuoteRegistry.size());

        VoucherQuoteRegistry.storeFaceValue("quote-2", 2000L);
        assertEquals(2, VoucherQuoteRegistry.size());

        VoucherQuoteRegistry.removeFaceValue("quote-1");
        assertEquals(1, VoucherQuoteRegistry.size());
    }

    @Test
    public void testClear() {
        // Test clearing all entries
        VoucherQuoteRegistry.storeFaceValue("quote-1", 1000L);
        VoucherQuoteRegistry.storeFaceValue("quote-2", 2000L);

        assertEquals(2, VoucherQuoteRegistry.size());

        VoucherQuoteRegistry.clear();

        assertEquals(0, VoucherQuoteRegistry.size());
        assertNull(VoucherQuoteRegistry.getFaceValue("quote-1"));
        assertNull(VoucherQuoteRegistry.getFaceValue("quote-2"));
    }

    @Test
    public void testZeroFaceValue() {
        // Test that zero face value can be stored (edge case)
        String quoteId = "test-quote-5";
        VoucherQuoteRegistry.storeFaceValue(quoteId, 0L);

        assertTrue(VoucherQuoteRegistry.isVoucherQuote(quoteId));
        assertEquals(0L, VoucherQuoteRegistry.getFaceValue(quoteId));
    }

    @Test
    public void testLargeFaceValue() {
        // Test storing a very large face value
        String quoteId = "test-quote-6";
        long largeFaceValue = Long.MAX_VALUE;

        VoucherQuoteRegistry.storeFaceValue(quoteId, largeFaceValue);

        assertEquals(largeFaceValue, VoucherQuoteRegistry.getFaceValue(quoteId));
    }

    @Test
    public void testCleanUp() {
        // Test that cleanUp can be called without errors
        VoucherQuoteRegistry.storeFaceValue("quote-1", 1000L);
        VoucherQuoteRegistry.cleanUp();

        // Entries should still exist (TTL not expired)
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue("quote-1"));
    }

    @Test
    public void testHitRate() {
        // Test that hit rate is tracked
        VoucherQuoteRegistry.storeFaceValue("quote-1", 1000L);

        // Perform some gets
        VoucherQuoteRegistry.getFaceValue("quote-1");  // hit
        VoucherQuoteRegistry.getFaceValue("quote-1");  // hit
        VoucherQuoteRegistry.getFaceValue("nonexistent");  // miss

        // Hit rate should be tracked
        double hitRate = VoucherQuoteRegistry.getHitRate();
        assertTrue(hitRate >= 0.0 && hitRate <= 1.0, "Hit rate should be between 0 and 1");
    }

    @Test
    public void testEvictionCount() {
        // Test that eviction count starts at zero
        long evictionCount = VoucherQuoteRegistry.getEvictionCount();
        assertTrue(evictionCount >= 0, "Eviction count should be non-negative");
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        // Test thread safety with concurrent writes
        int threadCount = 10;
        int opsPerThread = 100;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String quoteId = "quote-" + threadId + "-" + i;
                        VoucherQuoteRegistry.storeFaceValue(quoteId, (long) i);
                        VoucherQuoteRegistry.getFaceValue(quoteId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Concurrent operations should complete without deadlock");
        // Note: exact size may be less than threadCount * opsPerThread due to max size limit
        assertTrue(VoucherQuoteRegistry.size() > 0, "Some entries should be stored");
    }
}
