package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Adapter interface used to bridge lock metrics to an external sink.
 *
 * <p>Implementations can forward lock timing information to Micrometer or any
 * other metrics backend. When no adapter is registered, metrics recording is a no-op.
 *
 * <h2>Metrics Tracked</h2>
 * <ul>
 *   <li><b>Lock wait time:</b> Time spent waiting to acquire a lock</li>
 *   <li><b>Lock hold time:</b> Time spent holding a lock</li>
 *   <li><b>Active locks:</b> Current number of active locks (gauge)</li>
 * </ul>
 */
public interface LockMetricsAdapter {

    /**
     * Records the time spent waiting to acquire a lock.
     *
     * @param lockType     the type of lock (e.g., "quote", "proof")
     * @param lockId       the identifier of the locked resource
     * @param waitTimeNanos time spent waiting for the lock in nanoseconds
     */
    void recordLockWait(String lockType, String lockId, long waitTimeNanos);

    /**
     * Records the time spent holding a lock.
     *
     * @param lockType      the type of lock (e.g., "quote", "proof")
     * @param lockId        the identifier of the locked resource
     * @param holdTimeNanos time spent holding the lock in nanoseconds
     */
    void recordLockHold(String lockType, String lockId, long holdTimeNanos);

    /**
     * Updates the gauge for active lock count.
     *
     * @param lockType   the type of lock (e.g., "quote", "proof")
     * @param activeCount current number of active locks
     */
    void updateActiveLockCount(String lockType, int activeCount);
}
