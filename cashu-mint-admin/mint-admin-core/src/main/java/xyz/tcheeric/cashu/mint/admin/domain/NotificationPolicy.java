package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Duration;
import java.util.Objects;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Policy describing how operators are notified about mint events.
 */
@Value
@Accessors(fluent = true)
public class NotificationPolicy {

    boolean emailEnabled;
    boolean webhookEnabled;
    Duration throttleInterval;
    AuditMetadata auditMetadata;

    public NotificationPolicy(final boolean emailEnabled,
                              final boolean webhookEnabled,
                              final Duration throttleInterval,
                              final AuditMetadata auditMetadata) {
        this.emailEnabled = emailEnabled;
        this.webhookEnabled = webhookEnabled;
        this.throttleInterval = validateInterval(throttleInterval);
        this.auditMetadata = Objects.requireNonNull(auditMetadata, "audit metadata must not be null");
    }

    private static Duration validateInterval(final Duration interval) {
        final Duration validated = Objects.requireNonNull(interval, "throttle interval must not be null");
        if (validated.isNegative()) {
            throw new IllegalArgumentException("throttle interval must not be negative");
        }
        return validated;
    }

    public NotificationPolicy updateEmail(final boolean enabled, final AuditMetadata metadata) {
        return new NotificationPolicy(enabled, webhookEnabled, throttleInterval,
            Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }

    public NotificationPolicy updateWebhook(final boolean enabled, final AuditMetadata metadata) {
        return new NotificationPolicy(emailEnabled, enabled, throttleInterval,
            Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }

    public NotificationPolicy updateThrottleInterval(final Duration interval, final AuditMetadata metadata) {
        return new NotificationPolicy(emailEnabled, webhookEnabled, validateInterval(interval),
            Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }
}
