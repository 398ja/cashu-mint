package xyz.tcheeric.cashu.mint.proto.audit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Simple in-memory implementation of {@link LifecycleAuditRepository}.
 */
public class InMemoryLifecycleAuditRepository implements LifecycleAuditRepository {

    private final Map<UUID, LifecycleAuditRecord> recordsByEventId = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> recordsByConfigurationRevision = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> recordsByNotificationPolicy = new HashMap<>();
    private final Object monitor = new Object();

    @Override
    public LifecycleAuditRecord save(LifecycleAuditRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (monitor) {
            LifecycleAuditRecord previous = recordsByEventId.put(record.id(), record);
            if (previous != null) {
                removeFromIndex(previous.configurationRevision().id(), previous.id(), recordsByConfigurationRevision);
                removeFromIndex(previous.notificationPolicy().id(), previous.id(), recordsByNotificationPolicy);
            }
            addToIndex(record.configurationRevision().id(), record.id(), recordsByConfigurationRevision);
            addToIndex(record.notificationPolicy().id(), record.id(), recordsByNotificationPolicy);
            return record;
        }
    }

    @Override
    public Optional<LifecycleAuditRecord> findByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        synchronized (monitor) {
            return Optional.ofNullable(recordsByEventId.get(eventId));
        }
    }

    @Override
    public List<LifecycleAuditRecord> findByConfigurationRevisionId(UUID configurationRevisionId) {
        Objects.requireNonNull(configurationRevisionId, "configurationRevisionId");
        synchronized (monitor) {
            return projectRecords(recordsByConfigurationRevision.get(configurationRevisionId));
        }
    }

    @Override
    public List<LifecycleAuditRecord> findByNotificationPolicyId(UUID notificationPolicyId) {
        Objects.requireNonNull(notificationPolicyId, "notificationPolicyId");
        synchronized (monitor) {
            return projectRecords(recordsByNotificationPolicy.get(notificationPolicyId));
        }
    }

    private List<LifecycleAuditRecord> projectRecords(Set<UUID> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }
        List<LifecycleAuditRecord> result = new ArrayList<>(recordIds.size());
        for (UUID recordId : recordIds) {
            LifecycleAuditRecord record = recordsByEventId.get(recordId);
            if (record != null) {
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    private void addToIndex(UUID key, UUID recordId, Map<UUID, LinkedHashSet<UUID>> index) {
        index.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(recordId);
    }

    private void removeFromIndex(UUID key, UUID recordId, Map<UUID, LinkedHashSet<UUID>> index) {
        LinkedHashSet<UUID> ids = index.get(key);
        if (ids == null) {
            return;
        }
        ids.remove(recordId);
        if (ids.isEmpty()) {
            index.remove(key);
        }
    }
}
