package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    @Test
    // Ensures the latest metadata exposes lifecycle context fields.
    void shouldExposeLatestLifecycleContext() {
        final AuditMetadata base = new AuditMetadata(
            "system",
            "pause",
            Instant.now(),
            List.of("scheduled_maintenance"),
            List.of("CHG-101"),
            new AutomationContext(true, "orchestrator", "run-22"));
        final AuditMetadata metadata = base.withLifecycleContext(
            ConfigurationRevisionId.of(7),
            new NotificationPolicy(true, true, Duration.ofMinutes(10), base));

        final AuditTrail trail = AuditTrail.create(metadata);

        assertThat(trail.latestReasonCodes()).containsExactly("scheduled_maintenance");
        assertThat(trail.latestTicketReferences()).containsExactly("CHG-101");
        assertThat(trail.latestAutomationContext()).isEqualTo(metadata.automationContext());
        assertThat(trail.latestLifecycleContext().configurationRevisionId()).isEqualTo(ConfigurationRevisionId.of(7));
        assertThat(trail.latestLifecycleContext().notificationPolicySnapshot()).isNotNull();
    }
}
