package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Event emitted when a configuration revision is applied to the mint.
 */
@Value
@Accessors(fluent = true)
public final class ConfigurationAppliedEvent implements ConfigurationLifecycleEvent {

    ConfigurationRevisionId revisionId;
    AuditMetadata metadata;
    ConfigurationDiff diff;

    public ConfigurationAppliedEvent(final ConfigurationRevisionId revisionId,
                                     final AuditMetadata metadata,
                                     final ConfigurationDiff diff) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.diff = Objects.requireNonNull(diff, "diff must not be null");
    }

    @Override
    public ConfigurationRevisionState state() {
        return ConfigurationRevisionState.APPLIED;
    }
}
