package xyz.tcheeric.cashu.mint.observability.metrics;

import xyz.tcheeric.cashu.mint.proto.metrics.LockMetricsAdapter;

/**
 * Bridges protocol lock metrics to Micrometer.
 *
 * <p>This adapter implements {@link LockMetricsAdapter} and forwards all lock metric
 * recordings to the {@link LockMetrics} class for Micrometer instrumentation.
 *
 * <p>The adapter is registered via {@link xyz.tcheeric.cashu.mint.proto.metrics.LockMetricsRecorder}
 * allowing lock managers in the protocol layer to emit metrics without depending on Micrometer.
 */
public class MicrometerLockMetricsAdapter implements LockMetricsAdapter {

    private final LockMetrics lockMetrics;

    /**
     * Creates a new adapter wrapping the given LockMetrics.
     *
     * @param lockMetrics the Micrometer-backed metrics instance
     */
    public MicrometerLockMetricsAdapter(LockMetrics lockMetrics) {
        this.lockMetrics = lockMetrics;
    }

    @Override
    public void recordLockWait(String lockType, String lockId, long waitTimeNanos) {
        // lockId is not included in metrics to avoid high cardinality
        // It's useful for logging but not for metrics aggregation
        lockMetrics.recordLockWait(lockType, waitTimeNanos);
    }

    @Override
    public void recordLockHold(String lockType, String lockId, long holdTimeNanos) {
        lockMetrics.recordLockHold(lockType, holdTimeNanos);
    }

    @Override
    public void updateActiveLockCount(String lockType, int activeCount) {
        lockMetrics.updateActiveLockCount(lockType, activeCount);
    }
}
