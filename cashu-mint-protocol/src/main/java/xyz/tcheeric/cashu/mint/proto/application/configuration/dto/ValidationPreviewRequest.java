package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request for a validation preview of an existing revision.
 */
public record ValidationPreviewRequest(
        UUID revisionId,
        String requestedBy
) {

    public ValidationPreviewRequest {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
