package xyz.tcheeric.cashu.mint.proto.util;

import xyz.tcheeric.cashu.mint.proto.metrics.LockMetricsRecorder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides per-quote locking to coordinate concurrent mint operations on the same quote.
 *
 * <p>This manager replaces the global {@code MINT_MELT_LOCK} to allow parallel processing
 * of different quotes while ensuring mutual exclusion for operations on the same quote.
 *
 * <p>The lock prevents double-mint attacks where two concurrent requests for the same
 * paid quote could both pass the payment verification check simultaneously.
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * try (QuoteLock lock = QuoteLockManager.lockQuote(quoteId)) {
 *     // Critical section: check payment, sign, persist
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. Lock instances are managed with reference counting
 * to allow cleanup when no threads are waiting on a quote.
 *
 * @see ProofLockManager for per-secret locking used in melt operations
 */
public final class QuoteLockManager {
    private static final ConcurrentHashMap<String, QuoteMutex> LOCKS = new ConcurrentHashMap<>();
    private static final String LOCK_TYPE = "quote";

    private QuoteLockManager() {
    }

    /**
     * Acquires an exclusive lock for the specified quote ID.
     *
     * <p>The returned {@link QuoteLock} must be closed to release the lock.
     * Use try-with-resources to ensure proper cleanup.
     *
     * @param quoteId the quote ID to lock
     * @return a lock handle that must be closed to release the lock
     * @throws NullPointerException if quoteId is null
     */
    public static QuoteLock lockQuote(String quoteId) {
        if (quoteId == null) {
            throw new NullPointerException("quoteId must not be null");
        }

        long waitStart = System.nanoTime();
        QuoteMutex mutex = incrementReference(quoteId);
        boolean acquired = false;
        try {
            mutex.lock.lock();
            acquired = true;
            long waitTime = System.nanoTime() - waitStart;
            LockMetricsRecorder.recordLockWait(LOCK_TYPE, quoteId, waitTime);
            LockMetricsRecorder.updateActiveLockCount(LOCK_TYPE, LOCKS.size());
            return new QuoteLockImpl(quoteId, mutex, System.nanoTime());
        } finally {
            if (!acquired) {
                decrementReference(quoteId, mutex);
            }
        }
    }

    /**
     * Returns the number of active locks (for monitoring/metrics).
     *
     * @return the number of quotes currently locked
     */
    public static int getActiveLockCount() {
        return LOCKS.size();
    }

    private static QuoteMutex incrementReference(String quoteId) {
        return LOCKS.compute(quoteId, (key, existing) -> {
            QuoteMutex mutex = existing;
            if (mutex == null) {
                mutex = new QuoteMutex();
            }
            mutex.referenceCount++;
            return mutex;
        });
    }

    private static void decrementReference(String quoteId, QuoteMutex mutex) {
        LOCKS.computeIfPresent(quoteId, (key, existing) -> {
            if (existing != mutex) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount <= 0) {
                return null; // Remove from map
            }
            return existing;
        });
    }

    /**
     * A lock handle for a specific quote. Must be closed to release the lock.
     */
    @FunctionalInterface
    public interface QuoteLock extends AutoCloseable {
        @Override
        void close();
    }

    private static final class QuoteMutex {
        private final ReentrantLock lock = new ReentrantLock();
        private int referenceCount;
    }

    private static final class QuoteLockImpl implements QuoteLock {
        private final String quoteId;
        private final QuoteMutex mutex;
        private final long acquiredAt;

        private QuoteLockImpl(String quoteId, QuoteMutex mutex, long acquiredAt) {
            this.quoteId = quoteId;
            this.mutex = mutex;
            this.acquiredAt = acquiredAt;
        }

        @Override
        public void close() {
            long holdTime = System.nanoTime() - acquiredAt;
            mutex.lock.unlock();
            decrementReference(quoteId, mutex);
            LockMetricsRecorder.recordLockHold(LOCK_TYPE, quoteId, holdTime);
            LockMetricsRecorder.updateActiveLockCount(LOCK_TYPE, LOCKS.size());
        }
    }
}
