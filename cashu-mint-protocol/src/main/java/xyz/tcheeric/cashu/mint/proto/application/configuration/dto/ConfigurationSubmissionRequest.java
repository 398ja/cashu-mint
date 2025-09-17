package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Objects;

/**
 * Request envelope for submitting a configuration revision.
 */
public record ConfigurationSubmissionRequest(
        ConfigurationRevisionDraft draft,
        boolean autoRequestApproval
) {

    public ConfigurationSubmissionRequest {
        Objects.requireNonNull(draft, "draft");
    }
}
