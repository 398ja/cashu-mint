package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ValueObjectValidationTest {

    // Ensures mint identifiers must provide a real UUID string.
    @Test
    void shouldRejectBlankMintIdentifier() {
        assertThatThrownBy(() -> MintId.fromString("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must not be blank");
    }

    // Ensures configuration revisions start at one and increase monotonically.
    @Test
    void shouldRejectNonPositiveConfigurationRevision() {
        assertThatThrownBy(() -> ConfigurationRevisionId.of(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    // Ensures audit metadata cannot be created with blank actors.
    @Test
    void shouldRejectAuditMetadataWithBlankActor() {
        assertThatThrownBy(() -> new AuditMetadata(" ", "action", Instant.parse("2024-01-01T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor");
    }
}
