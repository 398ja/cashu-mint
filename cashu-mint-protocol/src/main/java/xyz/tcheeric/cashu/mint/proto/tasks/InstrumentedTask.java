package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.metrics.TaskExecutionRecorder;

/**
 * Base task that wraps {@link #execute()} with lightweight metrics recording.
 *
 * <p>This avoids relying on Spring AOP proxies, which are not applied to tasks instantiated
 * with {@code new} inside the protocol module.
 *
 * @param <T> result type
 */
public abstract class InstrumentedTask<T> implements Task<T> {

    @Override
    public final T execute() throws CashuErrorException {
        return TaskExecutionRecorder.record(getClass().getSimpleName(), this::doExecute);
    }

    /**
     * Implement task logic here. Metrics are recorded by the final {@link #execute()} method.
     *
     * @return task result
     * @throws CashuErrorException if the task fails
     */
    protected abstract T doExecute() throws CashuErrorException;
}
