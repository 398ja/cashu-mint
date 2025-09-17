package xyz.tcheeric.cashu.mint.admin.domain;

/**
 * Marker interface for configuration revision lifecycle domain events.
 */
public sealed interface ConfigurationLifecycleEvent
    permits ConfigurationSubmittedEvent, ConfigurationValidatedEvent, ConfigurationValidationFailedEvent,
            ConfigurationApprovedEvent, ConfigurationAppliedEvent, ConfigurationRollbackEvent {

    ConfigurationRevisionId revisionId();

    ConfigurationRevisionState state();

    AuditMetadata metadata();
}
