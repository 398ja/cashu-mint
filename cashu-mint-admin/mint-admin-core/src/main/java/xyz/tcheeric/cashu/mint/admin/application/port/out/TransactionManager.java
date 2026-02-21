package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Coordinates transactional execution for application use cases.
 */
public interface TransactionManager {

    /**
     * Execute the supplied action within a transactional boundary.
     *
     * @param action the work to perform
     * @param <T> the result type
     * @return the value returned by the action
     */
    <T> T execute(Supplier<T> action);

    /**
     * Execute the supplied action within a transactional boundary.
     *
     * @param action the work to perform
     */
    default void executeVoid(final Runnable action) {
        Objects.requireNonNull(action, "transactional action must not be null");
        execute(() -> {
            action.run();
            return null;
        });
    }
}
