package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response with validation and diff preview for a revision.
 */
public record ValidationPreviewResponse(
        UUID revisionId,
        DiffArtifact diff,
        ValidationSummary validation,
        NextStepGuidance nextSteps
) {

    public ValidationPreviewResponse {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(nextSteps, "nextSteps");
    }
}
