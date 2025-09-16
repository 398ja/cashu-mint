package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AuditMetadataTest {

    @Test
    // Ensures valid metadata is created when inputs are provided.
    void shouldCreateAuditMetadataWhenFieldsValid() {
        final Instant now = Instant.now();

        final AuditMetadata metadata = new AuditMetadata("alice", "created", now);

        assertThat(metadata.actor()).isEqualTo("alice");
        assertThat(metadata.action()).isEqualTo("created");
        assertThat(metadata.timestamp()).isEqualTo(now);
    }

    @Test
    // Ensures actor validation rejects blank names.
    void shouldThrowWhenActorBlank() {
        assertThrows(IllegalArgumentException.class, () -> new AuditMetadata(" ", "action", Instant.now()));
    }

    @Test
    // Ensures action validation rejects blank names.
    void shouldThrowWhenActionBlank() {
        assertThrows(IllegalArgumentException.class, () -> new AuditMetadata("alice", "", Instant.now()));
    }

    @Test
    // Ensures timestamp must be provided.
    void shouldThrowWhenTimestampNull() {
        assertThrows(NullPointerException.class, () -> new AuditMetadata("alice", "action", null));
    }
}
