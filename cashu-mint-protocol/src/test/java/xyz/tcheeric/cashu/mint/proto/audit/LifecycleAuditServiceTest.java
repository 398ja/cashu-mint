package xyz.tcheeric.cashu.mint.proto.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LifecycleAuditServiceTest {

    private LifecycleAuditService service;
    private ConfigurationRevision configurationRevision;
    private NotificationPolicy notificationPolicy;

    @BeforeEach
    void setUp() {
        service = new LifecycleAuditService(new InMemoryLifecycleAuditRepository());
        configurationRevision = ConfigurationRevision.of("rev-1", Instant.parse("2024-10-01T10:15:30Z"));
        notificationPolicy = NotificationPolicy.of("notify-admins");
    }

    // Ensures recording a lifecycle event stores the provided details and associations.
    @Test
    void recordLifecycleEventStoresDetailsAndLinks() {
        LifecycleEvent event = LifecycleEvent.of(
                LifecycleEventType.CREATED,
                Instant.parse("2024-10-01T10:16:30Z"),
                Map.of("resource", "mint-config", "actor", "alice"));

        LifecycleAuditRecord record = service.recordLifecycleEvent(event, configurationRevision, notificationPolicy);

        assertThat(record.lifecycleEvent()).isEqualTo(event);
        assertThat(record.configurationRevision()).isEqualTo(configurationRevision);
        assertThat(record.notificationPolicy()).isEqualTo(notificationPolicy);
        assertThat(service.findByEventId(event.id())).contains(record);
    }

    // Ensures saving another record with the same event refreshes the configuration and policy indexes.
    @Test
    void recordLifecycleEventReplacesExistingLinks() {
        LifecycleEvent event = LifecycleEvent.of(
                LifecycleEventType.CREATED,
                Instant.parse("2024-10-01T10:16:30Z"),
                Map.of("resource", "mint-config"));

        service.recordLifecycleEvent(event, configurationRevision, notificationPolicy);

        ConfigurationRevision newRevision = ConfigurationRevision.of("rev-2", Instant.parse("2024-10-01T11:00:00Z"));
        NotificationPolicy newPolicy = NotificationPolicy.of("notify-operators");

        LifecycleAuditRecord updatedRecord = service.recordLifecycleEvent(event, newRevision, newPolicy);

        assertThat(service.findByConfigurationRevision(configurationRevision.id())).isEmpty();
        assertThat(service.findByNotificationPolicy(notificationPolicy.id())).isEmpty();
        assertThat(service.findByConfigurationRevision(newRevision.id())).containsExactly(updatedRecord);
        assertThat(service.findByNotificationPolicy(newPolicy.id())).containsExactly(updatedRecord);
    }
}
