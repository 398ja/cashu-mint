package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Event emitted when a configuration revision is submitted.
 */
@Value
@Accessors(fluent = true)
public final class ConfigurationSubmittedEvent implements ConfigurationLifecycleEvent {

    ConfigurationRevisionId revisionId;
    ConfigurationRevisionId previousRevisionId;
    AuditMetadata metadata;
    ConfigurationDiff diff;

    public ConfigurationSubmittedEvent(final ConfigurationRevisionId revisionId,
                                       final ConfigurationRevisionId previousRevisionId,
                                       final AuditMetadata metadata,
                                       final ConfigurationDiff diff) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.previousRevisionId = previousRevisionId;
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.diff = Objects.requireNonNull(diff, "diff must not be null");
    }

    @Override
    public ConfigurationRevisionState state() {
        return ConfigurationRevisionState.DRAFT;
    }
}
