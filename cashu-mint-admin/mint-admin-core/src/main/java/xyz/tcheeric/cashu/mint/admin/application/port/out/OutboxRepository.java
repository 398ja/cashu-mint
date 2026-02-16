package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Persistence contract for the transactional outbox used to dispatch integration events.
 */
public interface OutboxRepository {

    /**
     * Append a message to the outbox for later dispatch.
     *
     * @param message the message to persist
     */
    void append(OutboxMessage message);

    /**
     * Load the pending messages that should be dispatched.
     *
     * @param availableBefore upper bound for the availability timestamp
     * @param limit maximum number of messages to fetch
     * @return pending messages ordered by availability
     */
    List<OutboxMessage> findPending(Instant availableBefore, int limit);

    /**
     * Mark a message as successfully dispatched.
     *
     * @param eventId identifier of the outbox entry
     * @param dispatchedAt dispatch timestamp
     */
    void markDispatched(UUID eventId, Instant dispatchedAt);

    /**
     * Record a failed delivery attempt and schedule the next retry.
     *
     * @param eventId identifier of the outbox entry
     * @param attemptAt timestamp when the failed delivery attempt occurred
     * @param nextAttemptAt timestamp when the message should be retried
     */
    void recordFailure(UUID eventId, Instant attemptAt, Instant nextAttemptAt);
}
