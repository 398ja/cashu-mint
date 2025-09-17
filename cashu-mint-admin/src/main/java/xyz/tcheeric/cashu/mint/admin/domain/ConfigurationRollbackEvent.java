package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Event emitted when a configuration revision triggers a rollback.
 */
@Value
@Accessors(fluent = true)
public final class ConfigurationRollbackEvent implements ConfigurationLifecycleEvent {

    ConfigurationRevisionId revisionId;
    ConfigurationRevisionId targetRevisionId;
    AuditMetadata metadata;
    ConfigurationDiff diff;

    public ConfigurationRollbackEvent(final ConfigurationRevisionId revisionId,
                                      final ConfigurationRevisionId targetRevisionId,
                                      final AuditMetadata metadata,
                                      final ConfigurationDiff diff) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.targetRevisionId = Objects.requireNonNull(targetRevisionId, "target revision id must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.diff = Objects.requireNonNull(diff, "diff must not be null");
    }

    @Override
    public ConfigurationRevisionState state() {
        return ConfigurationRevisionState.ROLLED_BACK;
    }
}
