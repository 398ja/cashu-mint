package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response returned after completing a rollback.
 */
public record RollbackRevisionResponse(
        UUID appliedRevisionId,
        UUID restoredRevisionId,
        AuditReference auditReference,
        NextStepGuidance nextSteps
) {

    public RollbackRevisionResponse {
        Objects.requireNonNull(appliedRevisionId, "appliedRevisionId");
        Objects.requireNonNull(restoredRevisionId, "restoredRevisionId");
        Objects.requireNonNull(auditReference, "auditReference");
        Objects.requireNonNull(nextSteps, "nextSteps");
    }
}
