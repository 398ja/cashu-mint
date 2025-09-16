package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

/**
 * Signals a failure while publishing a lifecycle event to the transactional outbox.
 */
public class TransactionalOutboxPublishingException extends RuntimeException {

    public TransactionalOutboxPublishingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
