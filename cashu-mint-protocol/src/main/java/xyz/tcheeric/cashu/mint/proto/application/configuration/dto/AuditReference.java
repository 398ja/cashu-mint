package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Reference to an audit trail entry.
 */
public record AuditReference(
        String referenceId,
        Instant recordedAt,
        String recordedBy
) {

    public AuditReference {
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBy, "recordedBy");
    }
}
