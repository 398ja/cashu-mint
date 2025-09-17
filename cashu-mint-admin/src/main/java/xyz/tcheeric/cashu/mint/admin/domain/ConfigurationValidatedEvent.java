package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Event emitted when a configuration revision passes validation.
 */
@Value
@Accessors(fluent = true)
public final class ConfigurationValidatedEvent implements ConfigurationLifecycleEvent {

    ConfigurationRevisionId revisionId;
    AuditMetadata metadata;
    ValidationReport report;

    public ConfigurationValidatedEvent(final ConfigurationRevisionId revisionId,
                                       final AuditMetadata metadata,
                                       final ValidationReport report) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.report = Objects.requireNonNull(report, "report must not be null");
    }

    @Override
    public ConfigurationRevisionState state() {
        return ConfigurationRevisionState.VALIDATED;
    }
}
