package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable ordered history of checkpoints for a configuration revision.
 */
@Value
@Accessors(fluent = true)
public class ConfigurationRevisionHistory {

    List<ConfigurationRevisionCheckpoint> checkpoints;

    private ConfigurationRevisionHistory(final List<ConfigurationRevisionCheckpoint> checkpoints) {
        this.checkpoints = List.copyOf(checkpoints);
    }

    public static ConfigurationRevisionHistory initial(final ConfigurationRevisionState state,
                                                        final AuditMetadata metadata) {
        return new ConfigurationRevisionHistory(List.of(new ConfigurationRevisionCheckpoint(state, metadata)));
    }

    public ConfigurationRevisionHistory append(final ConfigurationRevisionState state, final AuditMetadata metadata) {
        final List<ConfigurationRevisionCheckpoint> updated = new ArrayList<>(checkpoints);
        updated.add(new ConfigurationRevisionCheckpoint(state, metadata));
        return new ConfigurationRevisionHistory(updated);
    }

    public ConfigurationRevisionCheckpoint latest() {
        return checkpoints.get(checkpoints.size() - 1);
    }

    public List<ConfigurationRevisionCheckpoint> asList() {
        return Collections.unmodifiableList(checkpoints);
    }

    public boolean hasState(final ConfigurationRevisionState state) {
        return checkpoints.stream().anyMatch(checkpoint -> checkpoint.state() == state);
    }

    public AuditMetadata latestMetadata() {
        return latest().metadata();
    }

    public ConfigurationRevisionState latestState() {
        return latest().state();
    }

    public void validateAgainst(final ConfigurationRevisionState expectedState, final String message) {
        Objects.requireNonNull(expectedState, "expectedState must not be null");
        if (latestState() != expectedState) {
            throw new IllegalStateException(message);
        }
    }
}
