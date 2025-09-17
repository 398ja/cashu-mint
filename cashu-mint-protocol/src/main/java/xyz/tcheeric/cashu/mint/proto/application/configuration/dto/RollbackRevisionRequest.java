package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to rollback an applied revision to a prior state.
 */
public record RollbackRevisionRequest(
        UUID appliedRevisionId,
        UUID rollbackTargetRevisionId,
        String requestedBy,
        String reason
) {

    public RollbackRevisionRequest {
        Objects.requireNonNull(appliedRevisionId, "appliedRevisionId");
        Objects.requireNonNull(rollbackTargetRevisionId, "rollbackTargetRevisionId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(reason, "reason");
    }
}
