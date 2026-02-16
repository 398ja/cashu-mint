package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import java.util.List;
import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Dispatches an outbox message to multiple handlers in order, ensuring every handler
 * is attempted even if earlier handlers fail. If any handler throws, the first exception
 * is rethrown as an {@link OutboxMessageHandlingException} after all handlers have run.
 */
public class CompositeOutboxMessageHandler implements OutboxMessageHandler {

    private final List<OutboxMessageHandler> handlers;

    public CompositeOutboxMessageHandler(final List<OutboxMessageHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        if (handlers.isEmpty()) {
            throw new IllegalArgumentException("at least one handler is required");
        }
        this.handlers = List.copyOf(handlers);
    }

    @Override
    public void handle(final OutboxMessage message) {
        Objects.requireNonNull(message, "outbox message must not be null");
        RuntimeException firstFailure = null;
        for (final OutboxMessageHandler handler : handlers) {
            try {
                handler.handle(message);
            } catch (final RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (firstFailure != null) {
            if (firstFailure instanceof OutboxMessageHandlingException) {
                throw firstFailure;
            }
            throw new OutboxMessageHandlingException("Composite handler failed", firstFailure);
        }
    }
}
