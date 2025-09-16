package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Immutable representation of a lifecycle change for a mint aggregate.
 */
public record MintLifecycleEvent(MintLifecycleEventType type,
                                 MintId mintId,
                                 LifecycleState.State previousState,
                                 LifecycleState.State currentState,
                                 ConfigurationRevisionId configurationRevisionId,
                                 String versionTag,
                                 AuditMetadata auditMetadata) {

    public MintLifecycleEvent {
        Objects.requireNonNull(type, "event type must not be null");
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(currentState, "current state must not be null");
        Objects.requireNonNull(configurationRevisionId, "configuration revision must not be null");
        if (versionTag == null || versionTag.isBlank()) {
            throw new IllegalArgumentException("version tag must not be blank");
        }
        Objects.requireNonNull(auditMetadata, "audit metadata must not be null");
    }

    public static MintLifecycleEvent created(final MintId mintId,
                                             final LifecycleState.State currentState,
                                             final ConfigurationRevisionId revisionId,
                                             final String versionTag,
                                             final AuditMetadata auditMetadata) {
        return new MintLifecycleEvent(MintLifecycleEventType.CREATED, mintId, null, currentState, revisionId,
            versionTag, auditMetadata);
    }

    public static MintLifecycleEvent configurationUpdated(final MintId mintId,
                                                          final LifecycleState.State currentState,
                                                          final ConfigurationRevisionId revisionId,
                                                          final String versionTag,
                                                          final AuditMetadata auditMetadata) {
        return new MintLifecycleEvent(MintLifecycleEventType.CONFIGURATION_UPDATED, mintId, currentState,
            currentState, revisionId, versionTag, auditMetadata);
    }

    public static MintLifecycleEvent paused(final MintId mintId,
                                            final LifecycleState.State previousState,
                                            final LifecycleState.State currentState,
                                            final ConfigurationRevisionId revisionId,
                                            final String versionTag,
                                            final AuditMetadata auditMetadata) {
        return new MintLifecycleEvent(MintLifecycleEventType.PAUSED, mintId, previousState, currentState, revisionId,
            versionTag, auditMetadata);
    }

    public static MintLifecycleEvent resumed(final MintId mintId,
                                             final LifecycleState.State previousState,
                                             final LifecycleState.State currentState,
                                             final ConfigurationRevisionId revisionId,
                                             final String versionTag,
                                             final AuditMetadata auditMetadata) {
        return new MintLifecycleEvent(MintLifecycleEventType.RESUMED, mintId, previousState, currentState, revisionId,
            versionTag, auditMetadata);
    }

    public static MintLifecycleEvent retired(final MintId mintId,
                                             final LifecycleState.State previousState,
                                             final LifecycleState.State currentState,
                                             final ConfigurationRevisionId revisionId,
                                             final String versionTag,
                                             final AuditMetadata auditMetadata) {
        return new MintLifecycleEvent(MintLifecycleEventType.RETIRED, mintId, previousState, currentState, revisionId,
            versionTag, auditMetadata);
    }

    /**
     * Supported lifecycle event types.
     */
    public enum MintLifecycleEventType {
        CREATED,
        CONFIGURATION_UPDATED,
        PAUSED,
        RESUMED,
        RETIRED
    }
}
