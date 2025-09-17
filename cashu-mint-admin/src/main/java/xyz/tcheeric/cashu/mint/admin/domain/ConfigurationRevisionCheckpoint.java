package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Captures a state change within a configuration revision's lifecycle.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationRevisionCheckpoint {

    ConfigurationRevisionState state;
    AuditMetadata metadata;

    public ConfigurationRevisionCheckpoint(final ConfigurationRevisionState state, final AuditMetadata metadata) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
