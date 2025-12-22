package xyz.tcheeric.cashu.mint.proto.metrics;

import xyz.tcheeric.cashu.common.util.CashuErrorException;

/**
 * Utility to record task execution metrics without requiring Spring AOP proxies.
 *
 * <p>Tasks call {@link #record(String, TaskCallable)} to execute their logic. If an adapter
 * is registered (e.g., by the observability module), metrics are forwarded; otherwise the
 * call is a no-op.
 */
public final class TaskExecutionRecorder {

    private static final TaskMetricsAdapter NO_OP = (taskName, durationNanos, success, error) -> { };

    private static volatile TaskMetricsAdapter adapter = NO_OP;

    private TaskExecutionRecorder() {
    }

    /**
     * Registers the adapter used for metrics reporting. Passing {@code null} resets to a
     * no-op adapter.
     *
     * @param newAdapter the adapter to use, or {@code null} to disable reporting
     */
    public static void register(TaskMetricsAdapter newAdapter) {
        adapter = newAdapter != null ? newAdapter : NO_OP;
    }

    /**
     * Executes the provided callable while recording timing and outcome information.
     *
     * @param taskName the task name to report
     * @param callable the task logic
     * @param <T>      result type
     * @return callable result
     * @throws CashuErrorException if the task throws a {@link CashuErrorException}
     */
    public static <T> T record(String taskName, TaskCallable<T> callable) throws CashuErrorException {
        long start = System.nanoTime();
        try {
            T result = callable.call();
            adapter.record(taskName, System.nanoTime() - start, true, null);
            return result;
        } catch (CashuErrorException | RuntimeException | Error e) {
            adapter.record(taskName, System.nanoTime() - start, false, e);
            throw e;
        } catch (Exception e) {
            adapter.record(taskName, System.nanoTime() - start, false, e);
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface TaskCallable<T> {
        T call() throws Exception;
    }
}
