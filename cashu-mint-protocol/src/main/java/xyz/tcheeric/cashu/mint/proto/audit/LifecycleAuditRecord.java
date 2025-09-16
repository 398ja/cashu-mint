package xyz.tcheeric.cashu.mint.proto.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Persists the association between a lifecycle event and related configuration/notification metadata.
 */
public record LifecycleAuditRecord(UUID id,
                                   LifecycleEvent lifecycleEvent,
                                   ConfigurationRevision configurationRevision,
                                   NotificationPolicy notificationPolicy) {

    public LifecycleAuditRecord {
        Objects.requireNonNull(lifecycleEvent, "lifecycleEvent");
        Objects.requireNonNull(configurationRevision, "configurationRevision");
        Objects.requireNonNull(notificationPolicy, "notificationPolicy");
        UUID eventId = lifecycleEvent.id();
        if (id == null) {
            id = eventId;
        } else if (!id.equals(eventId)) {
            throw new IllegalArgumentException("record id must match lifecycle event id");
        }
    }
}
