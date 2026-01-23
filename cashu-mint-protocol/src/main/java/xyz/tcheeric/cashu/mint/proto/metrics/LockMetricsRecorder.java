package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Utility to record lock metrics without requiring Spring AOP proxies.
 *
 * <p>Lock managers call methods on this class to report metrics. If an adapter
 * is registered (e.g., by the observability module), metrics are forwarded; otherwise the
 * call is a no-op.
 */
public final class LockMetricsRecorder {

    private static final LockMetricsAdapter NO_OP = new LockMetricsAdapter() {
        @Override
        public void recordLockWait(String lockType, String lockId, long waitTimeNanos) {
        }

        @Override
        public void recordLockHold(String lockType, String lockId, long holdTimeNanos) {
        }

        @Override
        public void updateActiveLockCount(String lockType, int activeCount) {
        }
    };

    private static volatile LockMetricsAdapter adapter = NO_OP;

    private LockMetricsRecorder() {
    }

    /**
     * Registers the adapter used for metrics reporting. Passing {@code null} resets to a
     * no-op adapter.
     *
     * @param newAdapter the adapter to use, or {@code null} to disable reporting
     */
    public static void register(LockMetricsAdapter newAdapter) {
        adapter = newAdapter != null ? newAdapter : NO_OP;
    }

    /**
     * Records the time spent waiting to acquire a lock.
     *
     * @param lockType      the type of lock (e.g., "quote", "proof")
     * @param lockId        the identifier of the locked resource
     * @param waitTimeNanos time spent waiting for the lock in nanoseconds
     */
    public static void recordLockWait(String lockType, String lockId, long waitTimeNanos) {
        adapter.recordLockWait(lockType, lockId, waitTimeNanos);
    }

    /**
     * Records the time spent holding a lock.
     *
     * @param lockType      the type of lock (e.g., "quote", "proof")
     * @param lockId        the identifier of the locked resource
     * @param holdTimeNanos time spent holding the lock in nanoseconds
     */
    public static void recordLockHold(String lockType, String lockId, long holdTimeNanos) {
        adapter.recordLockHold(lockType, lockId, holdTimeNanos);
    }

    /**
     * Updates the gauge for active lock count.
     *
     * @param lockType    the type of lock (e.g., "quote", "proof")
     * @param activeCount current number of active locks
     */
    public static void updateActiveLockCount(String lockType, int activeCount) {
        adapter.updateActiveLockCount(lockType, activeCount);
    }
}
