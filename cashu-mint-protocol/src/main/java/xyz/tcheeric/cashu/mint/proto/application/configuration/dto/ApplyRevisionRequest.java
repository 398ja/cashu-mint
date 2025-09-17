package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request for applying an approved configuration revision.
 */
public record ApplyRevisionRequest(
        UUID revisionId,
        boolean includeSecrets,
        String requestedBy
) {

    public ApplyRevisionRequest {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
