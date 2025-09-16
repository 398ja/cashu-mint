package xyz.tcheeric.cashu.mint.proto.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade around {@link LifecycleAuditRepository} that simplifies recording auditable lifecycle transitions.
 */
public class LifecycleAuditService {

    private final LifecycleAuditRepository repository;

    public LifecycleAuditService(LifecycleAuditRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Records an audit entry for the supplied lifecycle event.
     *
     * @param event the lifecycle transition
     * @param configurationRevision the configuration revision tied to the lifecycle event
     * @param notificationPolicy the notification policy that should react to the lifecycle event
     * @return the persisted record
     */
    public LifecycleAuditRecord recordLifecycleEvent(LifecycleEvent event,
                                                     ConfigurationRevision configurationRevision,
                                                     NotificationPolicy notificationPolicy) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(configurationRevision, "configurationRevision");
        Objects.requireNonNull(notificationPolicy, "notificationPolicy");
        LifecycleAuditRecord record = new LifecycleAuditRecord(event.id(), event, configurationRevision, notificationPolicy);
        return repository.save(record);
    }

    /**
     * Convenience overload that constructs the lifecycle event from primitives, ensuring details are captured consistently.
     *
     * @param type lifecycle stage of the event
     * @param occurredAt when the event occurred
     * @param details additional context associated with the lifecycle event
     * @param configurationRevision configuration revision reference
     * @param notificationPolicy notification policy reference
     * @return the persisted record
     */
    public LifecycleAuditRecord recordLifecycleEvent(LifecycleEventType type,
                                                     Instant occurredAt,
                                                     Map<String, String> details,
                                                     ConfigurationRevision configurationRevision,
                                                     NotificationPolicy notificationPolicy) {
        return recordLifecycleEvent(LifecycleEvent.of(type, occurredAt, details),
                configurationRevision,
                notificationPolicy);
    }

    /**
     * Retrieves a record by lifecycle event identifier.
     *
     * @param eventId the lifecycle event identifier
     * @return the resolved record if present
     */
    public Optional<LifecycleAuditRecord> findByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return repository.findByEventId(eventId);
    }

    /**
     * Retrieves records that reference the given configuration revision.
     *
     * @param configurationRevisionId the configuration revision identifier
     * @return list of audit records tied to the revision
     */
    public List<LifecycleAuditRecord> findByConfigurationRevision(UUID configurationRevisionId) {
        Objects.requireNonNull(configurationRevisionId, "configurationRevisionId");
        return repository.findByConfigurationRevisionId(configurationRevisionId);
    }

    /**
     * Retrieves records that reference the given notification policy.
     *
     * @param notificationPolicyId the notification policy identifier
     * @return list of audit records tied to the policy
     */
    public List<LifecycleAuditRecord> findByNotificationPolicy(UUID notificationPolicyId) {
        Objects.requireNonNull(notificationPolicyId, "notificationPolicyId");
        return repository.findByNotificationPolicyId(notificationPolicyId);
    }
}
