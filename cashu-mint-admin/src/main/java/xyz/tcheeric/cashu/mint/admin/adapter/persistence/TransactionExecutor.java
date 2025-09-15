package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executes units of work within a transactional boundary.
 */
public interface TransactionExecutor {

    <T> T execute(Supplier<T> action);

    default void executeVoid(final Runnable action) {
        Objects.requireNonNull(action, "transactional action must not be null");
        execute(() -> {
            action.run();
            return null;
        });
    }
}
