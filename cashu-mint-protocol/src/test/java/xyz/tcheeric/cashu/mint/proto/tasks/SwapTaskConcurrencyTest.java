package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for SwapTask to verify double-spend prevention via proof locking.
 *
 * These tests validate that the per-proof locking mechanism (ProofLockManager)
 * correctly serializes concurrent swap requests for the same proofs, preventing
 * double-spend attacks while allowing parallel swapping of different proofs.
 *
 * Note: These tests verify the locking mechanism directly rather than full SwapTask
 * execution to avoid the complexity of mocking all dependencies.
 */
public class SwapTaskConcurrencyTest {

    /**
     * Verifies that concurrent swap requests for the same proof secrets are serialized.
     * Only one request should be processing at a time due to per-proof locking.
     *
     * This test uses ProofLockManager directly to test serialization behavior
     * as used by SwapTask.doExecute().
     */
    @Test
    public void concurrentSwap_SameProofs_SerializesRequests() throws Exception {
        // Secrets that would be extracted from proofs in a real swap
        List<String> proofSecrets = List.of("secret-1", "secret-2", "secret-3");
        int numConcurrentRequests = 10;

        // Track concurrent execution within critical section
        AtomicInteger maxConcurrentInCriticalSection = new AtomicInteger(0);
        AtomicInteger currentInCriticalSection = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);

        var futures = IntStream.range(0, numConcurrentRequests)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        startGate.await();

                        // Use ProofLockManager (same as SwapTask uses)
                        try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(proofSecrets)) {
                            // Track concurrent execution within critical section
                            int concurrent = currentInCriticalSection.incrementAndGet();
                            maxConcurrentInCriticalSection.updateAndGet(max -> Math.max(max, concurrent));

                            // Simulate work in critical section (proof verification, signing, invalidation)
                            Thread.sleep(5);

                            currentInCriticalSection.decrementAndGet();
                            successCount.incrementAndGet();
                        }
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }))
                .toList();

        // Release all requests simultaneously
        startGate.countDown();

        // Wait for completion
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        executor.shutdown();

        // Due to per-proof locking, only 1 thread should be in critical section at a time
        assertEquals(1, maxConcurrentInCriticalSection.get(),
                "Per-proof locking should serialize access to critical section");

        // All requests should succeed (they're serialized, so each can proceed after the previous completes)
        assertEquals(numConcurrentRequests, successCount.get(),
                "All serialized requests should eventually succeed");
    }

    /**
     * Verifies that swaps with different proofs can execute in parallel.
     * This tests that per-proof locking allows parallelism across different proof sets.
     */
    @Test
    public void concurrentSwap_DifferentProofs_AllowsParallel() throws Exception {
        int numSwaps = 10;

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch allLocksAcquired = new CountDownLatch(numSwaps);
        CountDownLatch releaseAll = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        var futures = IntStream.range(0, numSwaps)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        // Each swap has unique proof secrets
                        List<String> proofSecrets = List.of(
                                "swap-" + i + "-secret-1",
                                "swap-" + i + "-secret-2"
                        );

                        // Use ProofLockManager directly to test parallel lock acquisition
                        try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(proofSecrets)) {
                            int concurrent = currentConcurrent.incrementAndGet();
                            maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                            // Signal that we've acquired our lock
                            allLocksAcquired.countDown();

                            // Wait for all locks to be acquired (proving parallel access)
                            releaseAll.await(10, TimeUnit.SECONDS);

                            currentConcurrent.decrementAndGet();
                            successCount.incrementAndGet();
                        }
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }))
                .toList();

        // Wait for all locks to be acquired
        assertTrue(allLocksAcquired.await(5, TimeUnit.SECONDS),
                "All locks should be acquired in parallel");

        // At this point, all locks should be held concurrently
        assertEquals(numSwaps, maxConcurrent.get(),
                "All " + numSwaps + " swaps with different proofs should be locked concurrently");

        // Release all threads
        releaseAll.countDown();

        // Wait for completion
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();

        assertEquals(numSwaps, successCount.get(),
                "All parallel swaps should succeed");
    }

    /**
     * Verifies that ProofLockManager prevents double-spend by serializing access.
     * This simulates the scenario where concurrent requests try to swap the same proofs.
     */
    @Test
    public void proofLock_PreventsDoubleSpend() throws Exception {
        List<String> proofSecrets = List.of("double-spend-secret-1", "double-spend-secret-2");
        int concurrentRequests = 20;

        // Simulate a "spent" flag that would normally be tracked in DB via InvalidateProofsTask
        AtomicInteger spentCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);

        var futures = IntStream.range(0, concurrentRequests)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        startGate.await();

                        try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(proofSecrets)) {
                            // Check if already spent (simulating proof state check)
                            if (spentCount.get() > 0) {
                                // Already spent - this would be an error response in real code
                                return "already_spent";
                            }

                            // Simulate processing time (verification, signing, etc.)
                            Thread.sleep(10);

                            // Mark as spent (simulating InvalidateProofsTask)
                            spentCount.incrementAndGet();
                            return "success";
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                }))
                .toList();

        // Release all requests simultaneously
        startGate.countDown();

        // Collect results
        long successCount = 0;
        long alreadySpentCount = 0;
        for (Future<String> future : futures) {
            String result = future.get(30, TimeUnit.SECONDS);
            if ("success".equals(result)) {
                successCount++;
            } else if ("already_spent".equals(result)) {
                alreadySpentCount++;
            }
        }

        executor.shutdown();

        // Exactly one request should succeed
        assertEquals(1, successCount,
                "Exactly one swap should succeed due to per-proof locking");

        // All others should see "already spent"
        assertEquals(concurrentRequests - 1, alreadySpentCount,
                "All other requests should see already_spent");

        // The spent count should be exactly 1
        assertEquals(1, spentCount.get(),
                "Proofs should only be spent once");
    }

    /**
     * Verifies that partial overlap in proof secrets still causes serialization.
     * If two swaps share any common secret, they must be serialized.
     */
    @Test
    public void concurrentSwap_PartialOverlap_SerializesRequests() throws Exception {
        // Two swaps with one overlapping secret
        List<String> swap1Secrets = List.of("common-secret", "unique-1");
        List<String> swap2Secrets = List.of("common-secret", "unique-2");

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch swap1InCriticalSection = new CountDownLatch(1);

        Future<Boolean> swap1 = executor.submit(() -> {
            try {
                startGate.await();
                try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(swap1Secrets)) {
                    int concurrent = currentConcurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                    // Signal that swap1 is in critical section
                    swap1InCriticalSection.countDown();

                    // Hold lock for a bit
                    Thread.sleep(50);

                    currentConcurrent.decrementAndGet();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> swap2 = executor.submit(() -> {
            try {
                startGate.await();
                // Wait a tiny bit to ensure swap1 gets the lock first
                Thread.sleep(5);

                try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(swap2Secrets)) {
                    int concurrent = currentConcurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                    Thread.sleep(10);

                    currentConcurrent.decrementAndGet();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        // Start both swaps
        startGate.countDown();

        // Wait for completion
        assertTrue(swap1.get(5, TimeUnit.SECONDS));
        assertTrue(swap2.get(5, TimeUnit.SECONDS));

        executor.shutdown();

        // Due to overlapping "common-secret", both should be serialized
        assertEquals(1, maxConcurrent.get(),
                "Swaps with overlapping secrets should be serialized");
    }

    /**
     * Verifies that empty or null secret lists don't cause issues.
     * This is a defensive programming test.
     */
    @Test
    public void proofLock_EmptySecrets_HandlesGracefully() throws Exception {
        // Empty list should return a no-op lock
        try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(List.of())) {
            // Should complete without issues
            assertTrue(true);
        }

        // Null should also be handled (depends on implementation)
        try (ProofLockManager.ProofLock lock = ProofLockManager.lockSecrets(null)) {
            // Should complete without issues
            assertTrue(true);
        }
    }
}
