package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.ManageNotificationsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.ManageNotificationsResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.NotificationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository.AlertRecord;

class ManageNotificationsInteractorTest {

    private static final String ALERT_ID = "alert-001";

    private InMemoryAlertRepository repository;
    private ManageNotificationsInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAlertRepository();
        interactor = new ManageNotificationsInteractor(repository);
    }

    // Verifies alerts can be created and escalated while preserving escalation history.
    @Test
    void shouldCreateAndEscalateAlert() {
        interactor.handle(new ManageNotificationsRequest(
            "mint-001",
            null,
            NotificationCommand.CREATE_ALERT,
            "v1",
            ALERT_ID,
            "critical",
            "Mint offline",
            Map.of("region", "us-east"),
            null,
            null));

        final ManageNotificationsResponse escalation = interactor.handle(new ManageNotificationsRequest(
            null,
            "pagerduty",
            NotificationCommand.ESCALATE_ALERT,
            "v1",
            ALERT_ID,
            null,
            null,
            null,
            null,
            "manual escalation"));

        assertThat(escalation.alertId()).isEqualTo(ALERT_ID);
        assertThat(escalation.escalations()).containsExactly("pagerduty");
        assertThat(escalation.message()).isEqualTo("Alert escalated to pagerduty");
    }

    // Confirms acknowledgement and silence state transitions are persisted for existing alerts.
    @Test
    void shouldPersistAcknowledgedAndSilencedState() {
        interactor.handle(new ManageNotificationsRequest(
            "mint-001",
            null,
            NotificationCommand.CREATE_ALERT,
            "v1",
            ALERT_ID,
            "warning",
            "High latency",
            Map.of(),
            null,
            null));

        interactor.handle(new ManageNotificationsRequest(
            null,
            null,
            NotificationCommand.ACKNOWLEDGE,
            "v1",
            ALERT_ID,
            null,
            null,
            null,
            null,
            "ack"));

        final ManageNotificationsResponse silenced = interactor.handle(new ManageNotificationsRequest(
            null,
            null,
            NotificationCommand.SILENCE_ALERT,
            "v1",
            ALERT_ID,
            null,
            null,
            null,
            30,
            "maintenance window"));

        assertThat(silenced.acknowledged()).isTrue();
        assertThat(silenced.silenced()).isTrue();
        assertThat(silenced.silenceMinutes()).isEqualTo(30);
    }

    // Ensures operations against unknown alerts fail with a not-found domain error.
    @Test
    void shouldRejectAcknowledgementForUnknownAlert() {
        assertThatThrownBy(() -> interactor.handle(new ManageNotificationsRequest(
            null,
            null,
            NotificationCommand.ACKNOWLEDGE,
            "v1",
            "missing-alert",
            null,
            null,
            null,
            null,
            "ack")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not found");
    }

    private static final class InMemoryAlertRepository implements AlertRepository {

        private final Map<String, AlertRecord> alerts = new ConcurrentHashMap<>();

        @Override
        public boolean create(final AlertRecord alert) {
            return alerts.putIfAbsent(alert.alertId(), alert) == null;
        }

        @Override
        public Optional<AlertRecord> findById(final String alertId) {
            return Optional.ofNullable(alerts.get(alertId));
        }

        @Override
        public void update(final AlertRecord alert) {
            alerts.put(alert.alertId(), alert);
        }

        @Override
        public void appendEscalation(final String alertId, final String policyId) {
            final AlertRecord existing = alerts.get(alertId);
            if (existing == null) {
                return;
            }
            final List<String> escalations = new ArrayList<>(existing.escalations());
            if (!escalations.contains(policyId)) {
                escalations.add(policyId);
            }
            alerts.put(alertId, new AlertRecord(
                existing.alertId(),
                existing.mintId(),
                existing.severity(),
                existing.summary(),
                existing.labels(),
                existing.acknowledged(),
                existing.silenced(),
                existing.silenceMinutes(),
                escalations));
        }

        @Override
        public List<AlertRecord> findAll() {
            return List.copyOf(alerts.values());
        }
    }
}
