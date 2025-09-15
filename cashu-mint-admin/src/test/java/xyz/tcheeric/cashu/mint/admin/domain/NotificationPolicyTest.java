package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class NotificationPolicyTest {

    // Ensures negative throttle durations are disallowed to avoid scheduler bugs.
    @Test
    void shouldRejectNegativeThrottleInterval() {
        final AuditMetadata audit = new AuditMetadata("system", "policy", Instant.parse("2024-01-01T00:00:00Z"));

        assertThatThrownBy(() -> new NotificationPolicy(true, false, Duration.ofSeconds(-5), audit))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
    }
}
