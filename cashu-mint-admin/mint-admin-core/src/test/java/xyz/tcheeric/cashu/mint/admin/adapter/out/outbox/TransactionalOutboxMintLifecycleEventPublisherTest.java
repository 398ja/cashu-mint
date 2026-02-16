package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleContext;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class TransactionalOutboxMintLifecycleEventPublisherTest {

    private RecordingOutboxRepository outboxRepository;
    private TransactionalOutboxMintLifecycleEventPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxRepository = new RecordingOutboxRepository();
        publisher = new TransactionalOutboxMintLifecycleEventPublisher(outboxRepository, new ObjectMapper());
    }

    // Ensures publishing a lifecycle event writes to the outbox with consistent identifiers.
    @Test
    void shouldPublishEventToOutboxAndProjections() {
        final MintLifecycleEvent event = MintLifecycleEvent.paused(
            MintId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
            LifecycleState.State.ACTIVE,
            LifecycleState.State.SUSPENDED,
            ConfigurationRevisionId.of(3),
            "v3",
            new AuditMetadata("system", "pause", Instant.parse("2024-01-01T00:00:00Z"),
                List.of(), List.of("INC-42"), AutomationContext.manual(), LifecycleContext.empty(),
                UUID.fromString("00000000-1111-2222-3333-444444444444"), "incident-42"));

        publisher.publish(event);

        assertThat(outboxRepository.messages).hasSize(1);
        final OutboxMessage message = outboxRepository.messages.getFirst();
        assertThat(message.aggregateType()).isEqualTo("MintAggregate");
        assertThat(message.eventType()).isEqualTo("PAUSED");
        assertThat(message.aggregateId()).isEqualTo(event.mintId());
        assertThat(message.payload()).contains("\"type\":\"PAUSED\"");
        assertThat(message.payload()).contains("\"requestId\":\"00000000-1111-2222-3333-444444444444\"");
        assertThat(message.payload()).contains("\"correlationId\":\"incident-42\"");
        assertThat(message.attributes()).containsEntry("schema", "admin.mint-lifecycle.v1");
        assertThat(message.attributes()).containsEntry("versionTag", "v3");
        assertThat(message.occurredAt()).isEqualTo(event.auditMetadata().timestamp());
    }

    // Verifies serialization failures surface as publishing exceptions and no projections are written.
    @Test
    void shouldWrapSerializationFailures() {
        final TransactionalOutboxMintLifecycleEventPublisher failingPublisher =
            new TransactionalOutboxMintLifecycleEventPublisher(outboxRepository, new FailingObjectMapper());

        final MintLifecycleEvent event = MintLifecycleEvent.created(
            MintId.of(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
            LifecycleState.State.PROVISIONED,
            ConfigurationRevisionId.of(1),
            "v1",
            new AuditMetadata("system", "create", Instant.parse("2024-02-01T00:00:00Z"),
                List.of(), List.of(), AutomationContext.manual()));

        assertThatThrownBy(() -> failingPublisher.publish(event))
            .isInstanceOf(TransactionalOutboxPublishingException.class);
        assertThat(outboxRepository.messages).isEmpty();
    }

    private static final class RecordingOutboxRepository implements OutboxRepository {

        private final List<OutboxMessage> messages = new ArrayList<>();

        @Override
        public void append(final OutboxMessage message) {
            messages.add(message);
        }

        @Override
        public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
            return List.of();
        }

        @Override
        public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
        }

        @Override
        public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
        }
    }

    private static final class FailingObjectMapper extends ObjectMapper {

        @Override
        public String writeValueAsString(final Object value) throws JsonProcessingException {
            throw new JsonProcessingException("boom") { };
        }
    }
}
