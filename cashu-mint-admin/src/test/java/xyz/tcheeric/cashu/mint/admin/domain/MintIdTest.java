package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class MintIdTest {

    @Test
    // Ensures fromString parses valid UUID representations.
    void shouldParseValidUuidFromString() {
        final String raw = "123e4567-e89b-12d3-a456-426614174000";

        final MintId mintId = MintId.fromString(raw);

        assertThat(mintId.value()).isEqualTo(UUID.fromString(raw));
        assertThat(mintId.asString()).isEqualTo(raw);
    }

    @Test
    // Ensures fromString rejects blank identifiers.
    void shouldRejectBlankIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> MintId.fromString(" "));
    }

    @Test
    // Ensures fromString rejects malformed UUID values.
    void shouldRejectInvalidUuid() {
        assertThrows(IllegalArgumentException.class, () -> MintId.fromString("not-a-uuid"));
    }
}
