package xyz.tcheeric.cashu.mint.proto.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Simple repository abstraction used to persist lifecycle audit records.
 */
public interface LifecycleAuditRepository {

    /**
     * Stores or updates the provided audit record.
     *
     * @param record the record to persist
     * @return the persisted instance
     */
    LifecycleAuditRecord save(LifecycleAuditRecord record);

    /**
     * Fetches a single record by its lifecycle event identifier.
     *
     * @param eventId the lifecycle event identifier
     * @return the record if present
     */
    Optional<LifecycleAuditRecord> findByEventId(UUID eventId);

    /**
     * Retrieves records associated with a configuration revision.
     *
     * @param configurationRevisionId the configuration revision identifier
     * @return ordered list of audit records (in insertion order)
     */
    List<LifecycleAuditRecord> findByConfigurationRevisionId(UUID configurationRevisionId);

    /**
     * Retrieves records associated with a notification policy.
     *
     * @param notificationPolicyId the notification policy identifier
     * @return ordered list of audit records (in insertion order)
     */
    List<LifecycleAuditRecord> findByNotificationPolicyId(UUID notificationPolicyId);
}
