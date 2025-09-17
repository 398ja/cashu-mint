package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an audit trail entry request.
 */
public record AuditEntry(
        UUID revisionId,
        String action,
        String performedBy,
        Instant performedAt,
        String details
) {

    public AuditEntry {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(performedBy, "performedBy");
        Objects.requireNonNull(performedAt, "performedAt");
        Objects.requireNonNull(details, "details");
    }
}
