package xyz.tcheeric.cashu.mint.proto.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LifecycleAuditFlowIntegrationTest {

    private final InMemoryLifecycleAuditRepository repository = new InMemoryLifecycleAuditRepository();
    private final LifecycleAuditService service = new LifecycleAuditService(repository);

    private final ConfigurationRevision revisionA = ConfigurationRevision.of("rev-a", Instant.parse("2024-10-01T09:00:00Z"));
    private final ConfigurationRevision revisionB = ConfigurationRevision.of("rev-b", Instant.parse("2024-10-02T09:00:00Z"));
    private final NotificationPolicy policyOps = NotificationPolicy.of("notify-ops");
    private final NotificationPolicy policyAdmins = NotificationPolicy.of("notify-admins");

    // Ensures retrieving by configuration revision returns all associated lifecycle events in insertion order.
    @Test
    void retrievingByConfigurationRevisionReturnsLinkedEvents() {
        LifecycleAuditRecord record1 = service.recordLifecycleEvent(
                LifecycleEventType.CREATED,
                Instant.parse("2024-10-03T08:00:00Z"),
                Map.of("stage", "bootstrap"),
                revisionA,
                policyOps);

        LifecycleAuditRecord record2 = service.recordLifecycleEvent(
                LifecycleEventType.UPDATED,
                Instant.parse("2024-10-03T09:00:00Z"),
                Map.of("stage", "rollout"),
                revisionA,
                policyAdmins);

        assertThat(service.findByConfigurationRevision(revisionA.id()))
                .containsExactly(record1, record2);
        assertThat(service.findByConfigurationRevision(revisionB.id())).isEmpty();
    }

    // Ensures retrieving by notification policy only returns lifecycle events tied to that policy.
    @Test
    void retrievingByNotificationPolicyScopesResults() {
        service.recordLifecycleEvent(
                LifecycleEventType.CREATED,
                Instant.parse("2024-10-04T08:00:00Z"),
                Map.of("stage", "bootstrap"),
                revisionA,
                policyOps);

        LifecycleAuditRecord policyRecord = service.recordLifecycleEvent(
                LifecycleEventType.DISABLED,
                Instant.parse("2024-10-04T10:00:00Z"),
                Map.of("stage", "pause"),
                revisionB,
                policyAdmins);

        service.recordLifecycleEvent(
                LifecycleEventType.ENABLED,
                Instant.parse("2024-10-04T11:00:00Z"),
                Map.of("stage", "resume"),
                revisionB,
                policyOps);

        List<LifecycleAuditRecord> results = service.findByNotificationPolicy(policyAdmins.id());

        assertThat(results).containsExactly(policyRecord);
    }
}
