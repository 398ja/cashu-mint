package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class NotificationPolicyTest {

    private static AuditMetadata metadata(final String action) {
        return new AuditMetadata("operator", action, Instant.now());
    }

    @Test
    // Ensures constructor stores the provided settings.
    void shouldCreateNotificationPolicy() {
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), metadata("create"));

        assertThat(policy.emailEnabled()).isTrue();
        assertThat(policy.webhookEnabled()).isFalse();
        assertThat(policy.throttleInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    // Ensures negative throttle intervals are rejected.
    void shouldRejectNegativeThrottleInterval() {
        assertThrows(IllegalArgumentException.class,
            () -> new NotificationPolicy(true, true, Duration.ofMinutes(-1), metadata("create")));
    }

    @Test
    // Ensures updateEmail toggles the flag and replaces the audit metadata.
    void shouldUpdateEmailChannel() {
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), metadata("create"));
        final AuditMetadata metadata = metadata("update-email");

        final NotificationPolicy updated = policy.updateEmail(false, metadata);

        assertThat(updated.emailEnabled()).isFalse();
        assertThat(updated.auditMetadata()).isEqualTo(metadata);
    }

    @Test
    // Ensures updateThrottleInterval validates input and updates the interval.
    void shouldUpdateThrottleInterval() {
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), metadata("create"));
        final AuditMetadata metadata = metadata("throttle");

        final NotificationPolicy updated = policy.updateThrottleInterval(Duration.ofMinutes(1), metadata);

        assertThat(updated.throttleInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(updated.auditMetadata()).isEqualTo(metadata);
    }
}
