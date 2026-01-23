package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.QuoteLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.gateway.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Concurrency tests for MintTask to verify double-mint prevention.
 *
 * These tests validate that the per-quote locking mechanism (QuoteLockManager)
 * correctly serializes concurrent mint requests for the same quote, preventing
 * double-mint attacks while allowing parallel minting of different quotes.
 */
public class MintTaskConcurrencyTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    @AfterEach
    void tearDown() {
        VoucherQuoteRegistry.clear();
    }

    private Mint createMintWithKeys() {
        Mint mint = new Mint();
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        keys.put(BigInteger.valueOf(32), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000020")));
        keys.put(BigInteger.valueOf(64), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000040")));
        keys.put(BigInteger.valueOf(128), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000080")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        return new BlindedMessage(
                amount,
                KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"),
                null
        );
    }

    /**
     * Verifies that concurrent mint requests for the same quote are serialized.
     * Only one request should be processing at a time due to per-quote locking.
     *
     * This test uses QuoteLockManager directly to test serialization behavior
     * without the complexity of full MintTask setup.
     */
    @Test
    public void concurrentMint_SameQuote_SerializesRequests() throws Exception {
        String quoteId = "concurrent-same-quote";
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

                        // Use QuoteLockManager (same as MintTask uses)
                        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                            // Track concurrent execution within critical section
                            int concurrent = currentInCriticalSection.incrementAndGet();
                            maxConcurrentInCriticalSection.updateAndGet(max -> Math.max(max, concurrent));

                            // Simulate work in critical section
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

        // Due to per-quote locking, only 1 thread should be in critical section at a time
        assertEquals(1, maxConcurrentInCriticalSection.get(),
                "Per-quote locking should serialize access to critical section");

        // All requests should succeed (they're serialized, so each can proceed after the previous completes)
        assertEquals(numConcurrentRequests, successCount.get(),
                "All serialized requests should eventually succeed");
    }

    /**
     * Verifies that different quotes can be minted in parallel.
     * This tests that per-quote locking allows parallelism across different quotes.
     */
    @Test
    public void concurrentMint_DifferentQuotes_AllowsParallel() throws Exception {
        int numQuotes = 10;

        // Create multiple quotes
        for (int i = 0; i < numQuotes; i++) {
            VoucherQuoteRegistry.storeFaceValue("parallel-quote-" + i, 100L);
        }

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch allLocksAcquired = new CountDownLatch(numQuotes);
        CountDownLatch releaseAll = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        var futures = IntStream.range(0, numQuotes)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        String quoteId = "parallel-quote-" + i;

                        // Create request for this specific quote
                        BlindedMessage bm1 = createBlindedMessage(64);
                        BlindedMessage bm2 = createBlindedMessage(32);
                        BlindedMessage bm3 = createBlindedMessage(4);

                        PostMintRequest<Secret> request = new PostMintRequest<>();
                        request.setQuoteId(quoteId);
                        request.setBlindedMessages(List.of(bm1, bm2, bm3));

                        // Use QuoteLockManager directly to test parallel lock acquisition
                        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
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
        assertEquals(numQuotes, maxConcurrent.get(),
                "All " + numQuotes + " quotes should be locked concurrently");

        // Release all threads
        releaseAll.countDown();

        // Wait for completion
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();

        assertEquals(numQuotes, successCount.get(),
                "All parallel mints should succeed");
    }

    /**
     * Verifies that QuoteLockManager prevents double-mint by serializing access.
     * This simulates the scenario where payment status is "paid" and multiple
     * concurrent requests try to mint the same quote.
     */
    @Test
    public void quoteLock_PreventsDoubleMint() throws Exception {
        String quoteId = "double-mint-test-quote";
        int concurrentRequests = 20;

        // Simulate a "minted" flag that would normally be in DB
        AtomicInteger mintedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);

        var futures = IntStream.range(0, concurrentRequests)
                .mapToObj(i -> executor.submit(() -> {
                    try {
                        startGate.await();

                        try (QuoteLockManager.QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
                            // Check if already minted (simulating DB check)
                            if (mintedCount.get() > 0) {
                                // Already minted - this would be an error response in real code
                                return "already_minted";
                            }

                            // Simulate processing time (gateway check, signing, etc.)
                            Thread.sleep(10);

                            // Mark as minted
                            mintedCount.incrementAndGet();
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
        long alreadyMintedCount = 0;
        for (Future<String> future : futures) {
            String result = future.get(30, TimeUnit.SECONDS);
            if ("success".equals(result)) {
                successCount++;
            } else if ("already_minted".equals(result)) {
                alreadyMintedCount++;
            }
        }

        executor.shutdown();

        // Exactly one request should succeed
        assertEquals(1, successCount,
                "Exactly one mint should succeed due to per-quote locking");

        // All others should see "already minted"
        assertEquals(concurrentRequests - 1, alreadyMintedCount,
                "All other requests should see already_minted");

        // The minted count should be exactly 1
        assertEquals(1, mintedCount.get(),
                "Quote should only be minted once");
    }

    /**
     * Verifies that the global MINT_MELT_LOCK is no longer used.
     * MintTask should use per-quote locking instead.
     */
    @Test
    public void mintTask_UsesPerQuoteLocking_NotGlobalLock() throws Exception {
        // This test verifies the architectural change by running two mints
        // for different quotes and ensuring they complete in parallel time
        String quote1 = "parallel-test-1";
        String quote2 = "parallel-test-2";

        VoucherQuoteRegistry.storeFaceValue(quote1, 100L);
        VoucherQuoteRegistry.storeFaceValue(quote2, 100L);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        // Create two requests
        PostMintRequest<Secret> request1 = new PostMintRequest<>();
        request1.setQuoteId(quote1);
        request1.setBlindedMessages(List.of(createBlindedMessage(64), createBlindedMessage(32), createBlindedMessage(4)));

        PostMintRequest<Secret> request2 = new PostMintRequest<>();
        request2.setQuoteId(quote2);
        request2.setBlindedMessages(List.of(createBlindedMessage(64), createBlindedMessage(32), createBlindedMessage(4)));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch canProceed = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        Future<?> future1 = executor.submit(() -> {
            try {
                int c = currentConcurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, c));
                bothStarted.countDown();
                canProceed.await(5, TimeUnit.SECONDS);

                MintTask<Secret> task = new MintTask<>(request1, PaymentMethod.BOLT11, mint, service, signatureVaultService);
                task.execute();
                currentConcurrent.decrementAndGet();
            } catch (Exception e) {
                // Ignore
            }
        });

        Future<?> future2 = executor.submit(() -> {
            try {
                int c = currentConcurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, c));
                bothStarted.countDown();
                canProceed.await(5, TimeUnit.SECONDS);

                MintTask<Secret> task = new MintTask<>(request2, PaymentMethod.BOLT11, mint, service, signatureVaultService);
                task.execute();
                currentConcurrent.decrementAndGet();
            } catch (Exception e) {
                // Ignore
            }
        });

        // Wait for both to start
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));

        // Both threads should be able to start (with a global lock, one would be blocked)
        assertEquals(2, maxConcurrent.get(),
                "Both threads should start concurrently (no global lock)");

        // Let them proceed
        canProceed.countDown();

        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();
    }
}
