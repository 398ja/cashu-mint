package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to trigger staged approvals for a revision.
 */
public record StagedApprovalRequest(
        UUID revisionId,
        String stage,
        String requestedBy
) {

    public StagedApprovalRequest {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
