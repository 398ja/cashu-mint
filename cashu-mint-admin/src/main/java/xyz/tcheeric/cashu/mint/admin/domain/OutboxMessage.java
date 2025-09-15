package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable representation of a message stored in the transactional outbox.
 */
@Value
@Accessors(fluent = true)
public class OutboxMessage {

    UUID eventId;
    MintId aggregateId;
    String aggregateType;
    String eventType;
    String payload;
    Map<String, String> attributes;
    Instant occurredAt;
    Instant availableAt;
    Instant lastAttemptAt;
    Instant dispatchedAt;
    int deliveryAttempts;

    public OutboxMessage(final UUID eventId,
                         final MintId aggregateId,
                         final String aggregateType,
                         final String eventType,
                         final String payload,
                         final Map<String, String> attributes,
                         final Instant occurredAt,
                         final Instant availableAt,
                         final Instant lastAttemptAt,
                         final Instant dispatchedAt,
                         final int deliveryAttempts) {
        this.eventId = Objects.requireNonNull(eventId, "event id must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregate id must not be null");
        this.aggregateType = requireNonBlank(aggregateType, "aggregate type");
        this.eventType = requireNonBlank(eventType, "event type");
        this.payload = requireNonBlank(payload, "payload");
        this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurred at must not be null");
        this.availableAt = Objects.requireNonNull(availableAt, "available at must not be null");
        this.lastAttemptAt = lastAttemptAt;
        this.dispatchedAt = dispatchedAt;
        if (deliveryAttempts < 0) {
            throw new IllegalArgumentException("delivery attempts must not be negative");
        }
        this.deliveryAttempts = deliveryAttempts;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public OutboxMessage scheduleRetry(final Instant attemptTime, final Instant nextAvailableAt) {
        final Instant lastAttempt = Objects.requireNonNull(attemptTime, "attempt time must not be null");
        final Instant nextAvailable = Objects.requireNonNull(nextAvailableAt, "next availability must not be null");
        return new OutboxMessage(eventId, aggregateId, aggregateType, eventType, payload, attributes, occurredAt,
            nextAvailable, lastAttempt, dispatchedAt, deliveryAttempts + 1);
    }

    public OutboxMessage markDispatched(final Instant dispatchTime) {
        final Instant dispatched = Objects.requireNonNull(dispatchTime, "dispatch time must not be null");
        return new OutboxMessage(eventId, aggregateId, aggregateType, eventType, payload, attributes, occurredAt,
            availableAt, dispatched, dispatched, deliveryAttempts);
    }
}
