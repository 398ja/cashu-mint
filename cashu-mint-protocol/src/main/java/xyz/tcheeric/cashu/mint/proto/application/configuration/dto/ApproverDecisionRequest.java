package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request captured from an approver decision.
 */
public record ApproverDecisionRequest(
        UUID revisionId,
        String stage,
        String approverId,
        Decision decision,
        String comment
) {

    public ApproverDecisionRequest {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(approverId, "approverId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(comment, "comment");
    }
}
