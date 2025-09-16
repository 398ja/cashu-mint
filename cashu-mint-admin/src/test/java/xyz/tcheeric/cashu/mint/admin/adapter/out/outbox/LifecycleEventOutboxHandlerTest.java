package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleContext;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class LifecycleEventOutboxHandlerTest {

    private RecordingAggregateViewRepository aggregateViewRepository;
    private RecordingHistoryRepository historyRepository;
    private LifecycleEventOutboxHandler handler;
    private RecordingOutboxRepository outboxRepository;
    private TransactionalOutboxMintLifecycleEventPublisher publisher;

    @BeforeEach
    void setUp() {
        aggregateViewRepository = new RecordingAggregateViewRepository();
        historyRepository = new RecordingHistoryRepository();
        handler = new LifecycleEventOutboxHandler(aggregateViewRepository, historyRepository, new ObjectMapper());
        outboxRepository = new RecordingOutboxRepository();
        publisher = new TransactionalOutboxMintLifecycleEventPublisher(outboxRepository, new ObjectMapper());
    }

    // Ensures handler reconstructs lifecycle events and updates both read models.
    @Test
    void shouldProjectLifecycleEventIntoReadModels() {
        final MintLifecycleEvent event = MintLifecycleEvent.resumed(
            MintId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
            LifecycleState.State.SUSPENDED,
            LifecycleState.State.ACTIVE,
            ConfigurationRevisionId.of(5),
            "v5",
            new AuditMetadata("system", "resume", Instant.parse("2024-03-01T12:00:00Z"),
                List.of("INC-99"), List.of(), new AutomationContext(true, "scheduler", "run-7"),
                LifecycleContext.empty(), UUID.fromString("99999999-8888-7777-6666-555555555555"), "resume-incident"));

        publisher.publish(event);
        final OutboxMessage message = outboxRepository.messages.getFirst();

        final MintLifecycleEvent processed = handler.handle(message);

        assertThat(processed).usingRecursiveComparison().isEqualTo(event);
        assertThat(aggregateViewRepository.projectedEvents).containsExactly(event);
        assertThat(historyRepository.appended).hasSize(1);
        final RecordingHistoryRepository.Entry entry = historyRepository.appended.getFirst();
        assertThat(entry.event()).isEqualTo(event);
        assertThat(entry.eventId()).isEqualTo(message.eventId());
        assertThat(processed.auditMetadata().requestId()).isEqualTo(UUID.fromString("99999999-8888-7777-6666-555555555555"));
        assertThat(processed.auditMetadata().correlationId()).isEqualTo("resume-incident");
    }

    // Ensures payload mismatches result in a handling exception before projections are updated.
    @Test
    void shouldRejectPayloadWithMismatchedEventId() {
        final MintLifecycleEvent event = MintLifecycleEvent.paused(
            MintId.of(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
            LifecycleState.State.ACTIVE,
            LifecycleState.State.SUSPENDED,
            ConfigurationRevisionId.of(2),
            "v2",
            new AuditMetadata("system", "pause", Instant.parse("2024-03-02T12:00:00Z"),
                List.of(), List.of("INC-21"), AutomationContext.manual()));

        publisher.publish(event);
        final OutboxMessage original = outboxRepository.messages.getFirst();
        final OutboxMessage tampered = new OutboxMessage(UUID.randomUUID(), original.aggregateId(), original.aggregateType(),
            original.eventType(), original.payload(), original.attributes(), original.occurredAt(), original.availableAt(),
            original.lastAttemptAt(), original.dispatchedAt(), original.deliveryAttempts());

        assertThatThrownBy(() -> handler.handle(tampered))
            .isInstanceOf(OutboxMessageHandlingException.class)
            .hasMessageContaining("event id");
        assertThat(aggregateViewRepository.projectedEvents).isEmpty();
        assertThat(historyRepository.appended).isEmpty();
    }

    // Ensures deserialization failures surface as handling exceptions without mutating read models.
    @Test
    void shouldWrapDeserializationFailures() {
        final OutboxMessage malformed = new OutboxMessage(UUID.randomUUID(),
            MintId.of(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
            "MintAggregate",
            "CREATED",
            "{not-json}",
            java.util.Map.of(),
            Instant.parse("2024-03-03T12:00:00Z"),
            Instant.parse("2024-03-03T12:00:00Z"),
            null,
            null,
            0);

        assertThatThrownBy(() -> handler.handle(malformed))
            .isInstanceOf(OutboxMessageHandlingException.class)
            .hasMessageContaining("deserialize");
        assertThat(aggregateViewRepository.projectedEvents).isEmpty();
        assertThat(historyRepository.appended).isEmpty();
    }

    private static final class RecordingAggregateViewRepository implements MintAggregateViewRepository {

        private final List<MintLifecycleEvent> projectedEvents = new ArrayList<>();

        @Override
        public void upsert(final MintLifecycleEvent event) {
            projectedEvents.add(event);
        }

        @Override
        public java.util.Optional<MintAggregateView> findById(final MintId mintId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<MintAggregateView> findAll() {
            return List.of();
        }
    }

    private static final class RecordingHistoryRepository implements MintLifecycleHistoryRepository {

        private final List<Entry> appended = new ArrayList<>();

        @Override
        public void append(final UUID eventId, final MintLifecycleEvent event) {
            appended.add(new Entry(eventId, event));
        }

        @Override
        public List<MintLifecycleHistoryEntry> findByMintId(final MintId mintId) {
            return List.of();
        }

        record Entry(UUID eventId, MintLifecycleEvent event) { }
    }

    private static final class RecordingOutboxRepository implements OutboxRepository {

        private final List<OutboxMessage> messages = new ArrayList<>();

        @Override
        public void append(final OutboxMessage message) {
            messages.add(message);
        }

        @Override
        public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
            return List.copyOf(messages);
        }

        @Override
        public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
        }

        @Override
        public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
        }
    }
}
