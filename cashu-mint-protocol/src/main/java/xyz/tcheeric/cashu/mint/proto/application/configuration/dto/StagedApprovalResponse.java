package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response after staging an approval sequence.
 */
public record StagedApprovalResponse(
        UUID revisionId,
        ApprovalStateSnapshot approvalState,
        AuditReference auditReference,
        NextStepGuidance nextSteps
) {

    public StagedApprovalResponse {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(auditReference, "auditReference");
        Objects.requireNonNull(nextSteps, "nextSteps");
    }
}
