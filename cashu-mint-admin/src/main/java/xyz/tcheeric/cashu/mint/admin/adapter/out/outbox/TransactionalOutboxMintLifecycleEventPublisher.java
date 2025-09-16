package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Publishes lifecycle domain events by writing to the transactional outbox and projecting read models.
 */
public class TransactionalOutboxMintLifecycleEventPublisher implements MintLifecycleEventPublisher {

    private static final String AGGREGATE_TYPE = "MintAggregate";
    private static final String EVENT_SCHEMA = "admin.mint-lifecycle.v1";

    private final OutboxRepository outboxRepository;
    private final MintAggregateViewRepository aggregateViewRepository;
    private final MintLifecycleHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxMintLifecycleEventPublisher(final OutboxRepository outboxRepository,
                                                          final MintAggregateViewRepository aggregateViewRepository,
                                                          final MintLifecycleHistoryRepository historyRepository,
                                                          final ObjectMapper objectMapper) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outbox repository must not be null");
        this.aggregateViewRepository =
            Objects.requireNonNull(aggregateViewRepository, "aggregate view repository must not be null");
        this.historyRepository =
            Objects.requireNonNull(historyRepository, "lifecycle history repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public void publish(final MintLifecycleEvent event) {
        Objects.requireNonNull(event, "mint lifecycle event must not be null");
        final UUID eventId = UUID.randomUUID();
        final OutboxMessage message = toOutboxMessage(eventId, event);
        outboxRepository.append(message);
        aggregateViewRepository.upsert(event);
        historyRepository.append(eventId, event);
    }

    private OutboxMessage toOutboxMessage(final UUID eventId, final MintLifecycleEvent event) {
        final Instant occurredAt = event.auditMetadata().timestamp();
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("schema", EVENT_SCHEMA);
        if (event.versionTag() != null && !event.versionTag().isBlank()) {
            attributes.put("versionTag", event.versionTag());
        }
        final String payload = writePayload(eventId, event);
        return new OutboxMessage(
            eventId,
            event.mintId(),
            AGGREGATE_TYPE,
            event.type().name(),
            payload,
            Map.copyOf(attributes),
            occurredAt,
            occurredAt,
            null,
            null,
            0);
    }

    private String writePayload(final UUID eventId, final MintLifecycleEvent event) {
        final Map<String, Object> automation = new HashMap<>();
        automation.put("automated", event.auditMetadata().automationContext().automated());
        automation.put("system", event.auditMetadata().automationContext().system());
        automation.put("runId", event.auditMetadata().automationContext().runId());

        final Map<String, Object> audit = new HashMap<>();
        audit.put("actor", event.auditMetadata().actor());
        audit.put("action", event.auditMetadata().action());
        audit.put("timestamp", event.auditMetadata().timestamp().toString());
        audit.put("reasonCodes", event.auditMetadata().reasonCodes());
        audit.put("ticketReferences", event.auditMetadata().ticketReferences());
        audit.put("automation", automation);

        final Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId.toString());
        body.put("type", event.type().name());
        body.put("mintId", event.mintId().asString());
        body.put("previousState", event.previousState() == null ? null : event.previousState().name());
        body.put("currentState", event.currentState().name());
        body.put("configurationRevision", event.configurationRevisionId().value());
        body.put("versionTag", event.versionTag());
        body.put("audit", audit);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (final JsonProcessingException ex) {
            throw new TransactionalOutboxPublishingException("Failed to serialise lifecycle event", ex);
        }
    }
}
