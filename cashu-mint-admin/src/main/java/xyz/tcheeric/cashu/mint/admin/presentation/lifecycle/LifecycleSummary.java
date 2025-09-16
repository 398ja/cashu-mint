package xyz.tcheeric.cashu.mint.admin.presentation.lifecycle;

import java.util.Objects;

/**
 * View model describing the outcome of a lifecycle action.
 */
public record LifecycleSummary(LifecycleAction operation,
                               String mintId,
                               String previousState,
                               String currentState,
                               String versionTag,
                               boolean changed,
                               String message) {

    public LifecycleSummary {
        operation = Objects.requireNonNull(operation, "operation");
        mintId = requireText(mintId, "mintId");
        currentState = requireText(currentState, "currentState");
        versionTag = requireText(versionTag, "versionTag");
        message = message == null ? "" : message;
    }

    public boolean idempotent() {
        return !changed;
    }

    private static String requireText(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
