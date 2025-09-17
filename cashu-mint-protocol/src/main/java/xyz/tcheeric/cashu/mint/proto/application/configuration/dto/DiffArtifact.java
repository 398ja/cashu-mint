package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents rendered diff details for a configuration revision.
 */
public record DiffArtifact(
        String fromRevisionRef,
        String toRevisionRef,
        String summary,
        List<String> impactedResources,
        Map<String, Object> metadata
) {

    public DiffArtifact {
        Objects.requireNonNull(fromRevisionRef, "fromRevisionRef");
        Objects.requireNonNull(toRevisionRef, "toRevisionRef");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(impactedResources, "impactedResources");
        Objects.requireNonNull(metadata, "metadata");
        impactedResources = List.copyOf(impactedResources);
        metadata = Map.copyOf(metadata);
    }
}
