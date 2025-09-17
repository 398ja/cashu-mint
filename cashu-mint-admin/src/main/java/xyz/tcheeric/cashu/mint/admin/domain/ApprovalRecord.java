package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Captures approval metadata for a configuration revision.
 */
@Value
@Accessors(fluent = true)
public class ApprovalRecord {

    ConfigurationRevisionId revisionId;
    AuditMetadata approver;
    Instant approvedAt;
    List<String> conditions;
    NotificationPolicySnapshot notificationPolicySnapshot;

    public ApprovalRecord(final ConfigurationRevisionId revisionId,
                          final AuditMetadata approver,
                          final Instant approvedAt,
                          final List<String> conditions,
                          final NotificationPolicySnapshot notificationPolicySnapshot) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.approver = Objects.requireNonNull(approver, "approver metadata must not be null");
        this.approvedAt = Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        this.conditions = sanitizeList(conditions);
        this.notificationPolicySnapshot = notificationPolicySnapshot;
    }

    private static List<String> sanitizeList(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(ApprovalRecord::sanitize)
            .toList();
    }

    private static String sanitize(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("condition must not be blank");
        }
        return sanitized;
    }
}
