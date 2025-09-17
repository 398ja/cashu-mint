package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.Map;
import java.util.Objects;

/**
 * Draft payload for a configuration revision submission.
 */
public record ConfigurationRevisionDraft(
        String scope,
        String submittedBy,
        Map<String, Object> proposedConfiguration,
        String summary,
        String reason,
        boolean includesSecrets
) {

    public ConfigurationRevisionDraft {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(submittedBy, "submittedBy");
        Objects.requireNonNull(proposedConfiguration, "proposedConfiguration");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(reason, "reason");
        proposedConfiguration = Map.copyOf(proposedConfiguration);
    }
}
