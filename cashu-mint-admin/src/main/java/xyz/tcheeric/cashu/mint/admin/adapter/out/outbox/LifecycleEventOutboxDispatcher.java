package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Polls the transactional outbox and delegates lifecycle events to a handler.
 */
public class LifecycleEventOutboxDispatcher {

    private static final Duration OVERFLOW_BACKOFF = Duration.ofHours(24);

    private final OutboxRepository outboxRepository;
    private final LifecycleEventOutboxHandler handler;
    private final Clock clock;
    private final Duration failureBackoff;

    public LifecycleEventOutboxDispatcher(final OutboxRepository outboxRepository,
                                          final LifecycleEventOutboxHandler handler,
                                          final Duration failureBackoff) {
        this(outboxRepository, handler, Clock.systemUTC(), failureBackoff);
    }

    public LifecycleEventOutboxDispatcher(final OutboxRepository outboxRepository,
                                          final LifecycleEventOutboxHandler handler,
                                          final Clock clock,
                                          final Duration failureBackoff) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outbox repository must not be null");
        this.handler = Objects.requireNonNull(handler, "outbox handler must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.failureBackoff = requirePositive(failureBackoff);
    }

    /**
     * Dispatch pending messages up to the provided batch size.
     *
     * @param batchSize maximum number of messages to dispatch
     * @return number of successfully dispatched messages
     */
    public int dispatchPending(final int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        final Instant now = clock.instant();
        final List<OutboxMessage> pending = outboxRepository.findPending(now, batchSize);
        int processed = 0;
        RuntimeException unexpectedFailure = null;
        for (final OutboxMessage message : pending) {
            try {
                handler.handle(message);
                outboxRepository.markDispatched(message.eventId(), clock.instant());
                processed++;
            } catch (final OutboxMessageHandlingException ex) {
                scheduleRetry(message);
            } catch (final RuntimeException ex) {
                scheduleRetry(message);
                if (unexpectedFailure == null) {
                    unexpectedFailure = ex;
                }
            }
        }
        if (unexpectedFailure != null) {
            throw unexpectedFailure;
        }
        return processed;
    }

    private void scheduleRetry(final OutboxMessage message) {
        final Instant attemptAt = clock.instant();
        final Duration backoff = backoffForAttempts(message.deliveryAttempts() + 1);
        final Instant retryAt = attemptAt.plus(backoff);
        outboxRepository.recordFailure(message.eventId(), attemptAt, retryAt);
    }

    private Duration backoffForAttempts(final int attempts) {
        final int exponent = Math.max(0, Math.min(5, attempts - 1));
        final long multiplier = 1L << exponent;
        try {
            return failureBackoff.multipliedBy(multiplier);
        } catch (final ArithmeticException ex) {
            return OVERFLOW_BACKOFF;
        }
    }

    private Duration requirePositive(final Duration value) {
        final Duration nonNull = Objects.requireNonNull(value, "failure backoff must not be null");
        if (nonNull.isZero() || nonNull.isNegative()) {
            throw new IllegalArgumentException("failure backoff must be positive");
        }
        return nonNull;
    }
}
