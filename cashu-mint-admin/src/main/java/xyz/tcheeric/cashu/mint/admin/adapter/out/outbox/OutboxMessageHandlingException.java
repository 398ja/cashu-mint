package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

/**
 * Signals an error while processing a message retrieved from the transactional outbox.
 */
public class OutboxMessageHandlingException extends RuntimeException {

    public OutboxMessageHandlingException(final String message) {
        super(message);
    }

    public OutboxMessageHandlingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
