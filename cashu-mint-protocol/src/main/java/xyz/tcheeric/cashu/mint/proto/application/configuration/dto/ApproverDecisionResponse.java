package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response after recording an approver decision.
 */
public record ApproverDecisionResponse(
        UUID revisionId,
        ApprovalStateSnapshot approvalState,
        AuditReference auditReference,
        NextStepGuidance nextSteps
) {

    public ApproverDecisionResponse {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(auditReference, "auditReference");
        Objects.requireNonNull(nextSteps, "nextSteps");
    }
}
