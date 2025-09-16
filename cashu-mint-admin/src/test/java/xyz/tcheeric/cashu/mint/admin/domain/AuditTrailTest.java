package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AuditTrailTest {

    @Test
    // Ensures create captures the initial entry and sets latest metadata accordingly.
    void shouldCreateAuditTrailWithInitialEntry() {
        final AuditMetadata initial = new AuditMetadata("alice", "created", Instant.now());

        final AuditTrail trail = AuditTrail.create(initial);

        assertThat(trail.entries()).containsExactly(initial);
        assertThat(trail.latestMetadata()).isEqualTo(initial);
    }

    @Test
    // Ensures append returns a new trail with the additional entry and updated metadata.
    void shouldAppendEntryAndReturnNewTrail() {
        final AuditMetadata initial = new AuditMetadata("alice", "created", Instant.now());
        final AuditTrail trail = AuditTrail.create(initial);
        final AuditMetadata update = new AuditMetadata("bob", "updated", Instant.now());

        final AuditTrail updatedTrail = trail.append(update);

        assertThat(updatedTrail.entries()).containsExactly(initial, update);
        assertThat(updatedTrail.latestMetadata()).isEqualTo(update);
        assertThat(trail.entries()).containsExactly(initial);
    }

    @Test
    // Ensures create rejects null entries.
    void shouldThrowWhenCreateWithNull() {
        assertThrows(NullPointerException.class, () -> AuditTrail.create(null));
    }

    @Test
    // Ensures append rejects null entries.
    void shouldThrowWhenAppendNull() {
        final AuditTrail trail = AuditTrail.create(new AuditMetadata("alice", "created", Instant.now()));

        assertThrows(NullPointerException.class, () -> trail.append(null));
    }
}
