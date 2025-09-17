package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Response after successfully applying a revision.
 */
public record ApplyRevisionResponse(
        UUID revisionId,
        AuditReference auditReference,
        NextStepGuidance nextSteps,
        Optional<SecretsCommitResult> secretsCommitResult
) {

    public ApplyRevisionResponse {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(auditReference, "auditReference");
        Objects.requireNonNull(nextSteps, "nextSteps");
        Objects.requireNonNull(secretsCommitResult, "secretsCommitResult");
    }
}
