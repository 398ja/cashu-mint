package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response for configuration submission workflows.
 */
public record ConfigurationSubmissionResponse(
        UUID revisionId,
        DiffArtifact diff,
        ValidationSummary validation,
        ApprovalStateSnapshot approvalState,
        AuditReference auditReference,
        NextStepGuidance nextSteps
) {

    public ConfigurationSubmissionResponse {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(auditReference, "auditReference");
        Objects.requireNonNull(nextSteps, "nextSteps");
    }
}
