package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;

/**
 * Outcome of committing secrets associated with a revision.
 */
public record SecretsCommitResult(
        boolean success,
        String referenceId,
        String notes
) {

    public SecretsCommitResult {
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(notes, "notes");
    }
}
