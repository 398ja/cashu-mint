package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
        assertThat(metadata.reasonCodes()).isEmpty();
        assertThat(metadata.ticketReferences()).isEmpty();
        assertThat(metadata.automationContext()).isEqualTo(AutomationContext.manual());
        assertThat(metadata.lifecycleContext().hasConfigurationRevision()).isFalse();
        assertThat(metadata.lifecycleContext().hasNotificationPolicySnapshot()).isFalse();
    }

    @Test
    // Ensures the extended constructor captures lifecycle details for auditing.
    void shouldCreateAuditMetadataWithExtendedFields() {
        final Instant now = Instant.now();
        final AutomationContext automation = new AutomationContext(true, "runbook", "job-42");

        final AuditMetadata metadata = new AuditMetadata(
            "alice",
            "suspended",
            now,
            List.of("policy_violation", "manual_override"),
            List.of("INC-123", "RFO-456"),
            automation);

        assertThat(metadata.reasonCodes()).containsExactly("policy_violation", "manual_override");
        assertThat(metadata.ticketReferences()).containsExactly("INC-123", "RFO-456");
        assertThat(metadata.automationContext()).isEqualTo(automation);
        assertThat(metadata.lifecycleContext().hasConfigurationRevision()).isFalse();
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

    @Test
    // Ensures reason codes cannot contain blank values.
    void shouldThrowWhenReasonCodesContainBlank() {
        assertThrows(IllegalArgumentException.class, () -> new AuditMetadata(
            "alice",
            "paused",
            Instant.now(),
            List.of("valid", " "),
            List.of(),
            AutomationContext.manual()));
    }

    @Test
    // Ensures null automation context defaults to manual.
    void shouldDefaultAutomationContextWhenNull() {
        final AuditMetadata metadata = new AuditMetadata(
            "alice",
            "resumed",
            Instant.now(),
            null,
            null,
            null);

        assertThat(metadata.reasonCodes()).isEmpty();
        assertThat(metadata.ticketReferences()).isEmpty();
        assertThat(metadata.automationContext()).isEqualTo(AutomationContext.manual());
    }

    @Test
    // Ensures lifecycle context captures configuration revision and notification policy snapshot details.
    void shouldAttachLifecycleContext() {
        final AuditMetadata metadata = new AuditMetadata("alice", "resume", Instant.now());
        final ConfigurationRevisionId revisionId = ConfigurationRevisionId.of(4);
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), metadata);

        final AuditMetadata enriched = metadata.withLifecycleContext(revisionId, policy);

        assertThat(enriched.lifecycleContext().configurationRevisionId()).isEqualTo(revisionId);
        assertThat(enriched.lifecycleContext().notificationPolicySnapshot()).isNotNull();
        final NotificationPolicySnapshot snapshot = enriched.lifecycleContext().notificationPolicySnapshot();
        assertThat(snapshot.emailEnabled()).isTrue();
        assertThat(snapshot.webhookEnabled()).isFalse();
        assertThat(snapshot.throttleInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(snapshot.auditActor()).isEqualTo(metadata.actor());
        assertThat(snapshot.auditAction()).isEqualTo(metadata.action());
    }
}
