package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AutomationContext;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleContext;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Processes lifecycle messages persisted in the transactional outbox.
 */
public class LifecycleEventOutboxHandler {

    private final MintAggregateViewRepository aggregateViewRepository;
    private final MintLifecycleHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    public LifecycleEventOutboxHandler(final MintAggregateViewRepository aggregateViewRepository,
                                       final MintLifecycleHistoryRepository historyRepository,
                                       final ObjectMapper objectMapper) {
        this.aggregateViewRepository =
            Objects.requireNonNull(aggregateViewRepository, "aggregate view repository must not be null");
        this.historyRepository =
            Objects.requireNonNull(historyRepository, "lifecycle history repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    /**
     * Handle the supplied outbox message, projecting the lifecycle change into read models.
     *
     * @param message the message to process
     * @return the reconstructed lifecycle event
     */
    public MintLifecycleEvent handle(final OutboxMessage message) {
        Objects.requireNonNull(message, "outbox message must not be null");
        final LifecycleEventPayload payload = readPayload(message);
        if (payload.eventId() != null && !message.eventId().toString().equals(payload.eventId())) {
            throw new OutboxMessageHandlingException("Outbox payload event id does not match message id: "
                + message.eventId());
        }
        final MintLifecycleEvent event = toEvent(payload);
        aggregateViewRepository.upsert(event);
        historyRepository.append(message.eventId(), event);
        return event;
    }

    private LifecycleEventPayload readPayload(final OutboxMessage message) {
        try {
            return objectMapper.readValue(message.payload(), LifecycleEventPayload.class);
        } catch (final JsonProcessingException ex) {
            throw new OutboxMessageHandlingException("Failed to deserialize lifecycle event payload", ex);
        }
    }

    private MintLifecycleEvent toEvent(final LifecycleEventPayload payload) {
        final MintLifecycleEvent.MintLifecycleEventType type = parseType(payload.type());
        final MintId mintId = MintId.fromString(requireNonBlank(payload.mintId(), "mintId"));
        final LifecycleState.State currentState = parseState(payload.currentState(), "currentState");
        final LifecycleState.State previousState = payload.previousState() == null || payload.previousState().isBlank()
            ? null
            : parseState(payload.previousState(), "previousState");
        final Long revisionValue = Objects.requireNonNull(payload.configurationRevision(),
            "configuration revision must not be null");
        final ConfigurationRevisionId revision = ConfigurationRevisionId.of(revisionValue);
        final String versionTag = requireNonBlank(payload.versionTag(), "versionTag");
        final AuditMetadata audit = toAuditMetadata(payload.audit());

        return switch (type) {
            case CREATED -> MintLifecycleEvent.created(mintId, currentState, revision, versionTag, audit);
            case CONFIGURATION_UPDATED -> MintLifecycleEvent.configurationUpdated(mintId, currentState, revision, versionTag,
                audit);
            case PAUSED -> MintLifecycleEvent.paused(mintId, requireState(previousState, type), currentState, revision,
                versionTag, audit);
            case RESUMED -> MintLifecycleEvent.resumed(mintId, requireState(previousState, type), currentState, revision,
                versionTag, audit);
            case RETIRED -> MintLifecycleEvent.retired(mintId, requireState(previousState, type), currentState, revision,
                versionTag, audit);
        };
    }

    private MintLifecycleEvent.MintLifecycleEventType parseType(final String type) {
        try {
            return MintLifecycleEvent.MintLifecycleEventType.valueOf(requireNonBlank(type, "type"));
        } catch (final IllegalArgumentException ex) {
            throw new OutboxMessageHandlingException("Unsupported lifecycle event type: " + type, ex);
        }
    }

    private LifecycleState.State parseState(final String value, final String field) {
        try {
            return LifecycleState.State.valueOf(requireNonBlank(value, field));
        } catch (final IllegalArgumentException ex) {
            throw new OutboxMessageHandlingException("Invalid lifecycle state for " + field + ": " + value, ex);
        }
    }

    private LifecycleState.State requireState(final LifecycleState.State state,
                                             final MintLifecycleEvent.MintLifecycleEventType type) {
        if (state == null) {
            throw new OutboxMessageHandlingException("Lifecycle event " + type + " missing previous state");
        }
        return state;
    }

    private AuditMetadata toAuditMetadata(final AuditPayload auditPayload) {
        if (auditPayload == null) {
            throw new OutboxMessageHandlingException("Lifecycle event payload missing audit metadata");
        }
        final Instant timestamp;
        try {
            timestamp = Instant.parse(requireNonBlank(auditPayload.timestamp(), "audit.timestamp"));
        } catch (final DateTimeParseException ex) {
            throw new OutboxMessageHandlingException("Invalid audit timestamp: " + auditPayload.timestamp(), ex);
        }
        final List<String> reasons = auditPayload.reasonCodes() == null ? List.of() : List.copyOf(auditPayload.reasonCodes());
        final List<String> tickets = auditPayload.ticketReferences() == null
            ? List.of()
            : List.copyOf(auditPayload.ticketReferences());
        final AutomationPayload automationPayload = auditPayload.automation();
        final AutomationContext automationContext = automationPayload == null
            ? AutomationContext.manual()
            : new AutomationContext(Boolean.TRUE.equals(automationPayload.automated()), automationPayload.system(),
                automationPayload.runId());
        final UUID requestId = parseRequestId(auditPayload.requestId());
        return new AuditMetadata(requireNonBlank(auditPayload.actor(), "audit.actor"),
            requireNonBlank(auditPayload.action(), "audit.action"),
            timestamp,
            reasons,
            tickets,
            automationContext,
            LifecycleContext.empty(),
            requestId,
            auditPayload.correlationId());
    }

    private String requireNonBlank(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new OutboxMessageHandlingException(field + " must not be blank");
        }
        return value;
    }

    private record LifecycleEventPayload(String eventId,
                                         String type,
                                         String mintId,
                                         String previousState,
                                         String currentState,
                                         Long configurationRevision,
                                         String versionTag,
                                         AuditPayload audit) { }

    private record AuditPayload(String actor,
                                String action,
                                String timestamp,
                                List<String> reasonCodes,
                                List<String> ticketReferences,
                                AutomationPayload automation,
                                String requestId,
                                String correlationId) { }

    private record AutomationPayload(Boolean automated, String system, String runId) { }

    private UUID parseRequestId(final String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(requestId.trim());
        } catch (final IllegalArgumentException ex) {
            throw new OutboxMessageHandlingException("Invalid audit request id: " + requestId, ex);
        }
    }
}
