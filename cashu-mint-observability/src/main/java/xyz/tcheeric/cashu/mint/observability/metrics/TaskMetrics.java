package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Metrics for Cashu Mint task execution.
 *
 * <p>This class provides metrics instrumentation for protocol tasks:
 * <ul>
 *   <li>Task execution duration (timers)</li>
 *   <li>Task success counts</li>
 *   <li>Task failure counts with error type</li>
 * </ul>
 *
 * <p>Instrumented tasks include:
 * <ul>
 *   <li>SwapTask</li>
 *   <li>MintTask, MintTokensTask</li>
 *   <li>MeltTask, MeltTokensTask</li>
 *   <li>SignBlindedMessageTask</li>
 *   <li>VerifyProofsTask, VerifyFeesTask</li>
 *   <li>CheckStateTask</li>
 *   <li>RestoreSignaturesTask</li>
 *   <li>MintQuoteTask, MeltQuoteTask</li>
 *   <li>VoucherMintQuoteTask</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_task_*}
 */
@Slf4j
public class TaskMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_task_";

    /**
     * Bucket boundaries for the task timer histogram. Without
     * {@code publishPercentileHistogram} Micrometer exports a Prometheus
     * <em>summary</em> (count/sum/max only), so every dashboard panel and alert
     * built on {@code cashu_mint_task_duration_seconds_bucket} reads "No data".
     * The expected-value range bounds how many buckets that costs.
     */
    private static final Duration MINIMUM_EXPECTED_DURATION = Duration.ofMillis(1);

    private static final Duration MAXIMUM_EXPECTED_DURATION = Duration.ofSeconds(10);

    private final MeterRegistry registry;

    // Cached timers and counters by task name to avoid re-registration
    private final ConcurrentHashMap<String, Timer> taskTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> taskSuccessCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> taskFailureCounters = new ConcurrentHashMap<>();

    /**
     * Creates a new TaskMetrics instance.
     *
     * @param registry the Micrometer registry to use
     */
    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
        log.debug("TaskMetrics initialized");
    }

    /**
     * Gets or creates a timer for the specified task.
     *
     * @param taskName the name of the task (e.g., "SwapTask")
     * @return the timer for the task
     */
    public Timer getTimer(String taskName) {
        return taskTimers.computeIfAbsent(normalizeTaskName(taskName), name ->
                Timer.builder(METRIC_PREFIX + "duration_seconds")
                        .description("Task execution duration")
                        .tag("task_name", name)
                        .publishPercentileHistogram()
                        .minimumExpectedValue(MINIMUM_EXPECTED_DURATION)
                        .maximumExpectedValue(MAXIMUM_EXPECTED_DURATION)
                        .register(registry));
    }

    /**
     * Records a successful task execution.
     *
     * @param taskName the name of the task
     */
    public void recordSuccess(String taskName) {
        String normalized = normalizeTaskName(taskName);
        getSuccessCounter(normalized).increment();
        log.trace("Task {} completed successfully", normalized);
    }

    /**
     * Records a failed task execution.
     *
     * @param taskName the name of the task
     * @param errorType the type of error (e.g., exception class simple name)
     */
    public void recordFailure(String taskName, String errorType) {
        String normalized = normalizeTaskName(taskName);
        getFailureCounter(normalized, errorType).increment();
        log.trace("Task {} failed with error type: {}", normalized, errorType);
    }

    /**
     * Records a task execution with duration.
     *
     * @param taskName the name of the task
     * @param durationNanos duration in nanoseconds
     * @param success whether the task succeeded
     * @param errorType error type if failed (null if success)
     */
    public void recordExecution(String taskName, long durationNanos, boolean success, String errorType) {
        String normalized = normalizeTaskName(taskName);

        // Record duration
        getTimer(normalized).record(durationNanos, TimeUnit.NANOSECONDS);

        // Record outcome
        if (success) {
            recordSuccess(normalized);
        } else {
            recordFailure(normalized, errorType != null ? errorType : "unknown");
        }
    }

    /**
     * Starts a timer sample for measuring task execution.
     *
     * @return a new timer sample
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops a timer sample and records to the specified task timer.
     *
     * @param sample the timer sample
     * @param taskName the name of the task
     * @return the duration in nanoseconds
     */
    public long stopTimer(Timer.Sample sample, String taskName) {
        return sample.stop(getTimer(taskName));
    }

    /**
     * Gets task execution count (success + failure) for a task.
     *
     * @param taskName the name of the task
     * @return total execution count
     */
    public double getExecutionCount(String taskName) {
        String normalized = normalizeTaskName(taskName);
        Timer timer = taskTimers.get(normalized);
        return timer != null ? timer.count() : 0;
    }

    /**
     * Gets the mean execution time for a task.
     *
     * @param taskName the name of the task
     * @param timeUnit the time unit for the result
     * @return mean execution time
     */
    public double getMeanExecutionTime(String taskName, TimeUnit timeUnit) {
        String normalized = normalizeTaskName(taskName);
        Timer timer = taskTimers.get(normalized);
        return timer != null ? timer.mean(timeUnit) : 0;
    }

    /**
     * Gets the maximum execution time for a task.
     *
     * @param taskName the name of the task
     * @param timeUnit the time unit for the result
     * @return maximum execution time
     */
    public double getMaxExecutionTime(String taskName, TimeUnit timeUnit) {
        String normalized = normalizeTaskName(taskName);
        Timer timer = taskTimers.get(normalized);
        return timer != null ? timer.max(timeUnit) : 0;
    }

    // Helper methods

    private Counter getSuccessCounter(String taskName) {
        return taskSuccessCounters.computeIfAbsent(taskName, name ->
                Counter.builder(METRIC_PREFIX + "success_total")
                        .description("Successful task executions")
                        .tag("task_name", name)
                        .register(registry));
    }

    private Counter getFailureCounter(String taskName, String errorType) {
        String key = taskName + "_" + errorType;
        return taskFailureCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "failure_total")
                        .description("Failed task executions")
                        .tag("task_name", taskName)
                        .tag("error_type", errorType)
                        .register(registry));
    }

    /**
     * Normalizes a task name for consistent metric labeling.
     *
     * <p>Converts class names like "xyz.tcheeric.cashu.mint.proto.tasks.SwapTask"
     * to "SwapTask".
     *
     * @param taskName the raw task name
     * @return normalized task name
     */
    private String normalizeTaskName(String taskName) {
        if (taskName == null || taskName.isEmpty()) {
            return "unknown";
        }

        // Extract simple class name if fully qualified
        int lastDot = taskName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < taskName.length() - 1) {
            return taskName.substring(lastDot + 1);
        }

        return taskName;
    }
}
