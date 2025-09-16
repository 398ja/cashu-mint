package xyz.tcheeric.cashu.mint.admin.domain;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable snapshot of notification policy state captured alongside lifecycle events.
 */
@Value
@Accessors(fluent = true)
public class NotificationPolicySnapshot {

    boolean emailEnabled;
    boolean webhookEnabled;
    Duration throttleInterval;
    String auditActor;
    String auditAction;
    Instant auditTimestamp;

    public NotificationPolicySnapshot(final boolean emailEnabled,
                                      final boolean webhookEnabled,
                                      final Duration throttleInterval,
                                      final String auditActor,
                                      final String auditAction,
                                      final Instant auditTimestamp) {
        this.emailEnabled = emailEnabled;
        this.webhookEnabled = webhookEnabled;
        this.throttleInterval = requireNonNull(throttleInterval, "throttle interval must not be null");
        this.auditActor = requireNonBlank(auditActor, "audit actor");
        this.auditAction = requireNonBlank(auditAction, "audit action");
        this.auditTimestamp = requireNonNull(auditTimestamp, "audit timestamp must not be null");
    }

    public static NotificationPolicySnapshot fromPolicy(final NotificationPolicy policy) {
        requireNonNull(policy, "notification policy must not be null");
        final AuditMetadata audit = policy.auditMetadata();
        return new NotificationPolicySnapshot(policy.emailEnabled(), policy.webhookEnabled(), policy.throttleInterval(),
            audit.actor(), audit.action(), audit.timestamp());
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
