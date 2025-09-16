package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runnable task that invokes the outbox dispatcher on a fixed batch size.
 */
public final class LifecycleEventOutboxTask implements Runnable {

    private final LifecycleEventOutboxDispatcher dispatcher;
    private final int batchSize;
    private final Consumer<? super RuntimeException> errorHandler;

    public LifecycleEventOutboxTask(final LifecycleEventOutboxDispatcher dispatcher, final int batchSize) {
        this(dispatcher, batchSize, ex -> { });
    }

    public LifecycleEventOutboxTask(final LifecycleEventOutboxDispatcher dispatcher,
                                    final int batchSize,
                                    final Consumer<? super RuntimeException> errorHandler) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.batchSize = batchSize;
        this.errorHandler = Objects.requireNonNull(errorHandler, "error handler must not be null");
    }

    @Override
    public void run() {
        try {
            dispatcher.dispatchPending(batchSize);
        } catch (final RuntimeException ex) {
            errorHandler.accept(ex);
        }
    }
}
