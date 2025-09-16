package xyz.tcheeric.cashu.mint.proto.audit;

/**
 * Enumerates the lifecycle stages that can trigger audit events.
 */
public enum LifecycleEventType {
    /** Configuration or resource was created. */
    CREATED,
    /** Configuration or resource was updated. */
    UPDATED,
    /** Configuration or resource was archived or deleted. */
    ARCHIVED,
    /** A notification policy or configuration was enabled. */
    ENABLED,
    /** A notification policy or configuration was disabled. */
    DISABLED
}
