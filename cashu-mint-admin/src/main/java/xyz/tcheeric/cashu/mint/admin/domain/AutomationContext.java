package xyz.tcheeric.cashu.mint.admin.domain;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Describes the automation system responsible for triggering an audit event.
 */
@Value
@Accessors(fluent = true)
public class AutomationContext {

    boolean automated;
    String system;
    String runId;

    public AutomationContext(final boolean automated, final String system, final String runId) {
        this.automated = automated;
        this.system = automated ? requireNonBlank(system, "system") : sanitize(system);
        this.runId = sanitize(runId);
    }

    public static AutomationContext manual() {
        return new AutomationContext(false, null, null);
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        final String sanitized = sanitize(value);
        if (sanitized == null || sanitized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return sanitized;
    }

    private static String sanitize(final String value) {
        if (value == null) {
            return null;
        }
        final String sanitized = value.strip();
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized;
    }
}
