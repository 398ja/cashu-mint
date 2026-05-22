package xyz.tcheeric.cashu.mint.proto.ports;

import java.util.Optional;

/**
 * Port for the append-only {@code webhook_event} table.
 *
 * <p>Spec 001: data-model § WebhookEvent. Callers insert with the resolved
 * outcome; the durable PK on {@code (provider, provider_event_id)} is the
 * idempotency key per FR-006. Replay deliveries with the same key throw
 * {@link DuplicateEventException}; the caller inspects the persisted row to
 * decide whether to record a follow-up {@code duplicate} or {@code tamper}
 * event.
 */
public interface WebhookEventRepository {

    /**
     * Inserts a webhook event. The implementation MUST throw
     * {@link DuplicateEventException} if a row with the same
     * {@code (provider, provider_event_id)} already exists.
     */
    WebhookEvent insert(WebhookEvent event) throws DuplicateEventException;

    /** Loads a previously persisted event, if any. */
    Optional<WebhookEvent> findById(String provider, String providerEventId);

    /**
     * Thrown when an insert collides on the {@code (provider, provider_event_id)}
     * primary key. Carries the existing row so the caller can decide whether the
     * new delivery is a benign duplicate or a tamper signal.
     */
    final class DuplicateEventException extends RuntimeException {

        private final transient WebhookEvent existing;

        public DuplicateEventException(WebhookEvent existing) {
            super("Webhook event already persisted: provider=" + existing.provider()
                    + " providerEventId=" + existing.providerEventId());
            this.existing = existing;
        }

        public WebhookEvent existing() {
            return existing;
        }
    }
}
