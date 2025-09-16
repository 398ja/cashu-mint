package xyz.tcheeric.cashu.mint.admin.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
    List<String> reasonCodes;
    List<String> ticketReferences;
    AutomationContext automationContext;
    LifecycleContext lifecycleContext;

    public AuditMetadata(final String actor, final String action, final Instant timestamp) {
        this(actor, action, timestamp, List.of(), List.of(), AutomationContext.manual(), LifecycleContext.empty());
    }

    public AuditMetadata(final String actor,
                         final String action,
                         final Instant timestamp,
                         final List<String> reasonCodes,
                         final List<String> ticketReferences,
                         final AutomationContext automationContext) {
        this(actor, action, timestamp, reasonCodes, ticketReferences, automationContext, LifecycleContext.empty());
    }

    public AuditMetadata(final String actor,
                         final String action,
                         final Instant timestamp,
                         final List<String> reasonCodes,
                         final List<String> ticketReferences,
                         final AutomationContext automationContext,
                         final LifecycleContext lifecycleContext) {
        this.actor = requireNonBlank(actor, "actor");
        this.action = requireNonBlank(action, "action");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.reasonCodes = sanitizeList(reasonCodes, "reason code");
        this.ticketReferences = sanitizeList(ticketReferences, "ticket reference");
        this.automationContext = automationContext == null ? AutomationContext.manual() : automationContext;
        this.lifecycleContext = lifecycleContext == null ? LifecycleContext.empty() : lifecycleContext;
    }

    public AuditMetadata withLifecycleContext(final ConfigurationRevisionId configurationRevisionId,
                                              final NotificationPolicy notificationPolicy) {
        final LifecycleContext context;
        if (configurationRevisionId == null && notificationPolicy == null) {
            context = LifecycleContext.empty();
        } else {
            final NotificationPolicySnapshot snapshot =
                notificationPolicy == null ? null : NotificationPolicySnapshot.fromPolicy(notificationPolicy);
            context = new LifecycleContext(configurationRevisionId, snapshot);
        }
        return new AuditMetadata(actor, action, timestamp, reasonCodes, ticketReferences, automationContext, context);
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static List<String> sanitizeList(final List<String> values, final String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(value -> sanitizeValue(value, fieldName))
            .collect(Collectors.toUnmodifiableList());
    }

    private static String sanitizeValue(final String value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return sanitized;
    }
}
