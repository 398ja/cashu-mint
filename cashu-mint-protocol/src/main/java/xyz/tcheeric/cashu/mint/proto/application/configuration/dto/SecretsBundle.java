package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of secrets retrieved for a revision.
 */
public record SecretsBundle(
        UUID revisionId,
        Map<String, String> secrets,
        Instant fetchedAt,
        String fetchedBy
) {

    public SecretsBundle {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(fetchedBy, "fetchedBy");
        secrets = Map.copyOf(secrets);
    }
}
