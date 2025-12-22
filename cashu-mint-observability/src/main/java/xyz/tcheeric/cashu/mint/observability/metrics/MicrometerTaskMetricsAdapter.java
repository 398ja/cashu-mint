package xyz.tcheeric.cashu.mint.observability.metrics;

import xyz.tcheeric.cashu.mint.proto.metrics.TaskMetricsAdapter;

/**
 * Bridges protocol task execution metrics to Micrometer.
 */
public class MicrometerTaskMetricsAdapter implements TaskMetricsAdapter {

    private final TaskMetrics taskMetrics;

    public MicrometerTaskMetricsAdapter(TaskMetrics taskMetrics) {
        this.taskMetrics = taskMetrics;
    }

    @Override
    public void record(String taskName, long durationNanos, boolean success, Throwable error) {
        String errorType = success ? null : errorType(error);
        taskMetrics.recordExecution(taskName, durationNanos, success, errorType);
    }

    private String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return error.getClass().getSimpleName();
    }
}
