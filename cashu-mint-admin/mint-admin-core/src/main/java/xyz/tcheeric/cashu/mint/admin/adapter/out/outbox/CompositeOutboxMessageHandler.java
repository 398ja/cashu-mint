package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import java.util.List;
import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Dispatches an outbox message to multiple handlers in order, failing fast on the first
 * error. This prevents partial-success scenarios where earlier handlers persist side
 * effects (e.g. lifecycle history keyed by event_id) while later handlers fail, causing
 * duplicate-key errors on retry.
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
        for (final OutboxMessageHandler handler : handlers) {
            handler.handle(message);
        }
    }
}
