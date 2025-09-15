package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigurationSetTest {

    // Ensures updating a parameter returns a new immutable configuration snapshot.
    @Test
    void shouldReturnNewInstanceWhenParameterIsUpdated() {
        final AuditMetadata initialAudit = new AuditMetadata("system", "seed-config", Instant.parse("2024-01-01T00:00:00Z"));
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("max_tokens", "100"),
            initialAudit);
        final AuditMetadata updateAudit = new AuditMetadata("operator", "update-config", Instant.parse("2024-01-02T00:00:00Z"));

        final ConfigurationSet updated = configuration.updateParameter("max_tokens", "200",
            ConfigurationRevisionId.of(2), updateAudit);

        assertThat(updated.revisionId().value()).isEqualTo(2);
        assertThat(updated.parameters()).containsEntry("max_tokens", "200");
        assertThat(configuration.parameters()).containsEntry("max_tokens", "100");
        assertThat(updated.auditMetadata()).isEqualTo(updateAudit);
    }
}
