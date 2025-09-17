package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event emitted for governance related changes.
 */
public record ConfigurationGovernanceEvent(
        UUID revisionId,
        String type,
        Instant occurredAt,
        Object payload
) {

    public ConfigurationGovernanceEvent {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }
}
