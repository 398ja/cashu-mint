package xyz.tcheeric.cashu.mint.proto.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Captures a specific revision of a configuration document.
 */
public record ConfigurationRevision(UUID id, String revision, Instant appliedAt) {

    public ConfigurationRevision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(appliedAt, "appliedAt");
        revision = revision.trim();
        if (revision.isEmpty()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
    }

    /**
     * Convenience factory to build a revision with an auto-generated identifier.
     *
     * @param revision the revision identifier (for example git hash or semantic version)
     * @param appliedAt when the revision took effect
     * @return the created revision descriptor
     */
    public static ConfigurationRevision of(String revision, Instant appliedAt) {
        return new ConfigurationRevision(UUID.randomUUID(), revision, appliedAt);
    }
}
