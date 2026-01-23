package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QuoteLockManager utility class.
 */
public class QuoteLockManagerTest {

    @Test
    public void testLockQuote_sameQuoteReturnsBlockingLock() throws Exception {
        // Verify that acquiring a lock for the same quote blocks until released
        String quoteId = "quote-123";
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch secondLockWaiting = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // First thread acquires lock and holds it
        Future<?> first = executor.submit(() -> {
            try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                firstLockAcquired.countDown();
                counter.incrementAndGet();
                // Wait for second thread to start waiting
                assertTrue(secondLockWaiting.await(5, TimeUnit.SECONDS));
                // Hold lock briefly
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for first thread to acquire lock
        assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS));

        // Second thread tries to acquire same lock
        Future<?> second = executor.submit(() -> {
            secondLockWaiting.countDown();
            try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                // Should only get here after first thread releases
                assertEquals(1, counter.get(), "Second thread should see counter=1 after first completes");
                counter.incrementAndGet();
            }
        });

        // Wait for both to complete
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertEquals(2, counter.get());
        executor.shutdown();
    }

    @Test
    public void testLockQuote_differentQuotesAllowParallelAccess() throws Exception {
        // Verify that different quotes can be locked concurrently
        int numQuotes = 10;
        CountDownLatch allLocksAcquired = new CountDownLatch(numQuotes);
        CountDownLatch releaseAll = new CountDownLatch(1);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        var futures = IntStream.range(0, numQuotes)
                .mapToObj(i -> executor.submit(() -> {
                    try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote("quote-" + i)) {
                        int current = concurrentCount.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        allLocksAcquired.countDown();
                        releaseAll.await(10, TimeUnit.SECONDS);
                        concurrentCount.decrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();

        // Wait for all locks to be acquired (proving parallel access)
        assertTrue(allLocksAcquired.await(5, TimeUnit.SECONDS),
                "All locks should be acquired concurrently");

        // All threads should be holding locks concurrently
        assertEquals(numQuotes, maxConcurrent.get(),
                "All " + numQuotes + " quotes should be locked concurrently");

        // Release all threads
        releaseAll.countDown();

        // Wait for completion
        for (var future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();
    }

    @Test
    public void testLockQuote_threadSafeUnderConcurrentAccess() throws Exception {
        // Verify thread-safety when many threads compete for same lock
        String quoteId = "concurrent-quote";
        int numThreads = 100;
        AtomicInteger counter = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);

        var futures = IntStream.range(0, numThreads)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        startGate.await();
                        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                            // Critical section: increment counter non-atomically to detect races
                            int current = counter.get();
                            Thread.yield(); // Increase chance of race condition without lock
                            counter.set(current + 1);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();

        // Release all threads simultaneously
        startGate.countDown();

        // Wait for all to complete
        for (var future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        // Without proper locking, counter would likely be < numThreads due to races
        assertEquals(numThreads, counter.get(),
                "Counter should equal thread count with proper locking");

        executor.shutdown();
    }

    @Test
    public void testLockQuote_lockCleanupAfterRelease() throws Exception {
        // Verify locks are cleaned up when no longer needed
        String quoteId = "cleanup-quote";

        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
            assertTrue(QuoteLockManager.getActiveLockCount() >= 1);
        }

        // After release, lock should be cleaned up eventually
        // Note: Due to reference counting, the lock is removed when count reaches 0
        assertEquals(0, QuoteLockManager.getActiveLockCount(),
                "Lock should be cleaned up after release");
    }

    @Test
    public void testLockQuote_activeLockCount() throws Exception {
        // Verify active lock count tracking
        assertEquals(0, QuoteLockManager.getActiveLockCount());

        try (QuoteLockManager.QuoteLock lock1 = QuoteLockManager.lockQuote("quote-1")) {
            assertEquals(1, QuoteLockManager.getActiveLockCount());

            try (QuoteLockManager.QuoteLock lock2 = QuoteLockManager.lockQuote("quote-2")) {
                assertEquals(2, QuoteLockManager.getActiveLockCount());
            }

            assertEquals(1, QuoteLockManager.getActiveLockCount());
        }

        assertEquals(0, QuoteLockManager.getActiveLockCount());
    }

    @Test
    public void testLockQuote_nullQuoteIdThrowsException() {
        // Verify null quote ID is rejected
        assertThrows(NullPointerException.class, () -> {
            QuoteLockManager.lockQuote(null);
        });
    }

    @Test
    public void testLockQuote_emptyQuoteIdIsAllowed() {
        // Empty string is a valid quote ID (though unusual)
        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote("")) {
            assertEquals(1, QuoteLockManager.getActiveLockCount());
        }
    }

    @Test
    public void testLockQuote_reentrantBehavior() throws Exception {
        // Test that the same thread can acquire the lock multiple times (ReentrantLock)
        String quoteId = "reentrant-quote";

        try (QuoteLockManager.QuoteLock lock1 = QuoteLockManager.lockQuote(quoteId)) {
            // Same thread acquiring same lock again should work (reentrant)
            try (QuoteLockManager.QuoteLock lock2 = QuoteLockManager.lockQuote(quoteId)) {
                // Both locks held
                assertTrue(QuoteLockManager.getActiveLockCount() >= 1);
            }
            // Still holding outer lock
        }
        // All locks released
        assertEquals(0, QuoteLockManager.getActiveLockCount());
    }

    @Test
    public void testLockQuote_preventDoubleMintScenario() throws Exception {
        // Simulate double-mint prevention scenario
        String quoteId = "paid-quote-123";
        ConcurrentHashMap<String, Boolean> signatures = new ConcurrentHashMap<>();
        AtomicInteger successfulMints = new AtomicInteger(0);
        AtomicInteger alreadyMintedErrors = new AtomicInteger(0);
        int concurrentRequests = 10;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);

        var futures = IntStream.range(0, concurrentRequests)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        startGate.await();
                        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                            // Check if already minted (simulating DB check)
                            if (signatures.containsKey(quoteId)) {
                                alreadyMintedErrors.incrementAndGet();
                                return;
                            }
                            // Simulate signing delay
                            Thread.sleep(10);
                            // Store signature (only first succeeds due to lock)
                            signatures.put(quoteId, true);
                            successfulMints.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();

        // Release all requests simultaneously
        startGate.countDown();

        // Wait for completion
        for (var future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertEquals(1, successfulMints.get(),
                "Exactly one mint should succeed");
        assertEquals(concurrentRequests - 1, alreadyMintedErrors.get(),
                "Other requests should fail with already-minted");

        executor.shutdown();
    }
}
