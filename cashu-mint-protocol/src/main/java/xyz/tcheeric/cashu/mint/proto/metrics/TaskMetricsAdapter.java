package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Adapter interface used to bridge task execution metrics to an external sink.
 *
 * <p>Implementations can forward timing and outcome information to Micrometer or any
 * other metrics backend. When no adapter is registered, metrics recording is a no-op.
 */
public interface TaskMetricsAdapter {

    /**
     * Records a single task execution.
     *
     * @param taskName      the simple name of the task class
     * @param durationNanos execution duration in nanoseconds
     * @param success       whether the task completed successfully
     * @param error         error that occurred (if any), otherwise {@code null}
     */
    void record(String taskName, long durationNanos, boolean success, Throwable error);
}
