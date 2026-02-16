package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    private static OutboxMessage messageWithAttempts(final int attempts) {
        return new OutboxMessage(UUID.fromString("123e4567-e89b-12d3-a456-426614174500"),
            MintId.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")), "mint", "event", "payload",
            Map.of("key", "value"), Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T01:00:00Z"),
            Instant.parse("2024-01-01T02:00:00Z"), Instant.parse("2024-01-01T03:00:00Z"), attempts);
    }

    @Test
    // Ensures constructor copies attributes and substitutes empty map when null.
    void shouldCreateOutboxMessageWithDefaults() {
        final OutboxMessage message = new OutboxMessage(UUID.fromString("123e4567-e89b-12d3-a456-426614174500"),
            MintId.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")), "mint", "event", "payload",
            null, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T01:00:00Z"),
            Instant.parse("2024-01-01T02:00:00Z"), Instant.parse("2024-01-01T03:00:00Z"), 0);

        assertThat(message.attributes()).isEmpty();
        assertThat(message.deliveryAttempts()).isZero();
    }

    @Test
    // Ensures negative delivery attempts are rejected.
    void shouldRejectNegativeDeliveryAttempts() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutboxMessage(UUID.randomUUID(), MintId.of(UUID.randomUUID()), "mint", "event", "payload", Map.of(),
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), -1));
    }

    @Test
    // Ensures scheduleRetry increments attempts and updates timestamps.
    void shouldScheduleRetryIncrementAttempts() {
        final OutboxMessage message = messageWithAttempts(1);
        final Instant attemptTime = Instant.parse("2024-01-01T04:00:00Z");
        final Instant nextAvailable = Instant.parse("2024-01-01T05:00:00Z");

        final OutboxMessage retried = message.scheduleRetry(attemptTime, nextAvailable);

        assertThat(retried.deliveryAttempts()).isEqualTo(2);
        assertThat(retried.lastAttemptAt()).isEqualTo(attemptTime);
        assertThat(retried.availableAt()).isEqualTo(nextAvailable);
    }

    @Test
    // Ensures markDispatched sets dispatch timestamps without altering availability.
    void shouldMarkDispatchedSetTimestamps() {
        final OutboxMessage message = messageWithAttempts(2);
        final Instant dispatchedAt = Instant.parse("2024-01-01T06:00:00Z");

        final OutboxMessage dispatched = message.markDispatched(dispatchedAt);

        assertThat(dispatched.dispatchedAt()).isEqualTo(dispatchedAt);
        assertThat(dispatched.availableAt()).isEqualTo(message.availableAt());
    }
}
