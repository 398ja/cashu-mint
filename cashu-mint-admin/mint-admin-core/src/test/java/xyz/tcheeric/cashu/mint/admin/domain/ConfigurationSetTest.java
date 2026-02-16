package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigurationSetTest {

    private static AuditMetadata metadata() {
        return new AuditMetadata("alice", "create-config", Instant.now());
    }

    @Test
    // Ensures constructor copies values and exposes immutable parameters.
    void shouldCreateConfigurationSetWithValidatedInputs() {
        final ConfigurationSet configurationSet = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("key", "value"), metadata());

        assertThat(configurationSet.parameters()).containsEntry("key", "value");
        assertThat(configurationSet.parameters()).isUnmodifiable();
    }

    @Test
    // Ensures blank keys are rejected when constructing the configuration set.
    void shouldThrowWhenParameterKeyBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigurationSet(ConfigurationRevisionId.of(1), Map.of(" ", "value"), metadata()));
    }

    @Test
    // Ensures blank values are rejected when constructing the configuration set.
    void shouldThrowWhenParameterValueBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigurationSet(ConfigurationRevisionId.of(1), Map.of("key", ""), metadata()));
    }

    @Test
    // Ensures updateParameter enforces monotonically increasing revisions.
    void shouldThrowWhenNextRevisionNotGreater() {
        final ConfigurationSet configurationSet = new ConfigurationSet(ConfigurationRevisionId.of(2),
            Map.of("key", "value"), metadata());

        assertThrows(IllegalArgumentException.class,
            () -> configurationSet.updateParameter("key", "new", ConfigurationRevisionId.of(2), metadata()));
    }

    @Test
    // Ensures updateParameter returns a new configuration with updated values and revision.
    void shouldUpdateParameterAndAdvanceRevision() {
        final ConfigurationSet configurationSet = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("key", "value"), metadata());
        final ConfigurationRevisionId nextRevision = ConfigurationRevisionId.of(2);

        final ConfigurationSet updated = configurationSet.updateParameter("key", "new", nextRevision, metadata());

        assertThat(updated.parameters()).containsEntry("key", "new");
        assertThat(updated.revisionId()).isEqualTo(nextRevision);
        assertThat(configurationSet.parameters()).containsEntry("key", "value");
    }
}
