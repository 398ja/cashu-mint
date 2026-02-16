package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class LifecycleEventOutboxDispatcherTest {

    private RecordingOutboxRepository outboxRepository;
    private StubLifecycleEventOutboxHandler handler;
    private Clock clock;
    private Duration backoff;
    private LifecycleEventOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outboxRepository = new RecordingOutboxRepository();
        handler = new StubLifecycleEventOutboxHandler();
        clock = Clock.fixed(Instant.parse("2024-04-01T00:00:00Z"), ZoneOffset.UTC);
        backoff = Duration.ofMinutes(5);
        dispatcher = new LifecycleEventOutboxDispatcher(outboxRepository, handler, clock, backoff);
    }

    // Ensures dispatcher marks messages dispatched when the handler succeeds.
    @Test
    void shouldDispatchPendingMessages() {
        final OutboxMessage message = message(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), 0);
        outboxRepository.queue(message);

        final int processed = dispatcher.dispatchPending(10);

        assertThat(processed).isEqualTo(1);
        assertThat(handler.handledMessages).containsExactly(message);
        assertThat(outboxRepository.marked).containsExactly(message.eventId());
        assertThat(outboxRepository.failures).isEmpty();
    }

    // Ensures handling exceptions schedule retries and allow subsequent messages to succeed.
    @Test
    void shouldRecordFailureAndContinueOnHandlingError() {
        final OutboxMessage failing = message(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), 1);
        final OutboxMessage succeeding = message(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), 0);
        outboxRepository.queue(failing);
        outboxRepository.queue(succeeding);
        handler.failWithHandlingException(failing.eventId());

        final int processed = dispatcher.dispatchPending(5);

        assertThat(processed).isEqualTo(1);
        assertThat(handler.handledMessages).containsExactlyInAnyOrder(failing, succeeding);
        assertThat(outboxRepository.marked).containsExactly(succeeding.eventId());
        assertThat(outboxRepository.failures).hasSize(1);
        final FailureRecord failure = outboxRepository.failures.getFirst();
        assertThat(failure.eventId()).isEqualTo(failing.eventId());
        assertThat(failure.attemptAt()).isEqualTo(clock.instant());
        assertThat(failure.nextAttempt()).isEqualTo(clock.instant().plus(backoff.multipliedBy(2)));
    }

    // Ensures unexpected runtime errors propagate after recording the failure.
    @Test
    void shouldPropagateUnexpectedRuntimeException() {
        final OutboxMessage failing = message(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"), 2);
        final OutboxMessage succeeding = message(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), 0);
        outboxRepository.queue(failing);
        outboxRepository.queue(succeeding);
        handler.failWithRuntimeException(failing.eventId());

        assertThatThrownBy(() -> dispatcher.dispatchPending(5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");
        assertThat(handler.handledMessages).containsExactlyInAnyOrder(failing, succeeding);
        assertThat(outboxRepository.marked).containsExactly(succeeding.eventId());
        assertThat(outboxRepository.failures).hasSize(1);
        final FailureRecord failure = outboxRepository.failures.getFirst();
        assertThat(failure.eventId()).isEqualTo(failing.eventId());
        assertThat(failure.nextAttempt()).isEqualTo(clock.instant().plus(backoff.multipliedBy(4)));
    }

    private OutboxMessage message(final UUID eventId, final int attempts) {
        final MintId mintId = MintId.of(UUID.randomUUID());
        return new OutboxMessage(eventId, mintId, "MintAggregate", LifecycleState.State.ACTIVE.name(),
            Map.of("payload", "value").toString(),
            Map.of("schema", "admin.mint-lifecycle.v1"),
            clock.instant(),
            clock.instant(),
            null,
            null,
            attempts);
    }

    private static final class RecordingOutboxRepository implements OutboxRepository {

        private final List<OutboxMessage> pending = new ArrayList<>();
        private final List<UUID> marked = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<FailureRecord> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        void queue(final OutboxMessage message) {
            pending.add(message);
        }

        @Override
        public void append(final OutboxMessage message) {
            pending.add(message);
        }

        @Override
        public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
            return List.copyOf(pending);
        }

        @Override
        public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
            marked.add(eventId);
        }

        @Override
        public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
            failures.add(new FailureRecord(eventId, attemptAt, nextAttemptAt));
        }
    }

    private static final class StubLifecycleEventOutboxHandler implements OutboxMessageHandler {

        private final List<OutboxMessage> handledMessages = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<UUID> handlingFailures = new ArrayList<>();
        private final List<UUID> runtimeFailures = new ArrayList<>();

        @Override
        public void handle(final OutboxMessage message) {
            handledMessages.add(message);
            if (handlingFailures.contains(message.eventId())) {
                throw new OutboxMessageHandlingException("simulated failure");
            }
            if (runtimeFailures.contains(message.eventId())) {
                throw new IllegalStateException("boom");
            }
        }

        void failWithHandlingException(final UUID eventId) {
            handlingFailures.add(eventId);
        }

        void failWithRuntimeException(final UUID eventId) {
            runtimeFailures.add(eventId);
        }
    }

    private record FailureRecord(UUID eventId, Instant attemptAt, Instant nextAttempt) { }
}
