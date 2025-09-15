package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable audit metadata describing who performed an action, what they did, and when.
 */
@Value
@Accessors(fluent = true)
public class AuditMetadata {

    String actor;
    String action;
    Instant timestamp;

    public AuditMetadata(final String actor, final String action, final Instant timestamp) {
        this.actor = requireNonBlank(actor, "actor");
        this.action = requireNonBlank(action, "action");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
