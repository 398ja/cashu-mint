package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class AlertWorkflowE2EIT extends AbstractAdminE2EIT {

    // Verifies create, acknowledge, silence, escalate, and unsilence alert workflows via admin API.
    @Test
    void shouldExecuteAlertLifecycleWorkflow() {
        final String alertId = "alert-" + UUID.randomUUID();
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/alerts",
            Map.of(
                "alertId", alertId,
                "mintId", mintId,
                "severity", "CRITICAL",
                "summary", "E2E alert",
                "labels", Map.of("env", "e2e"),
                "requestedBy", actor("Alerts Admin")),
            ALERTS_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> acknowledged = adminApiClient().post(
            "/admin/alerts/" + alertId + "/acknowledge",
            Map.of("requestedBy", actor("Alerts Admin"), "reason", "triaged"),
            ALERTS_ADMIN_ROLE);
        assertThat(acknowledged.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> silenced = adminApiClient().post(
            "/admin/alerts/" + alertId + "/silence",
            Map.of("requestedBy", actor("Alerts Admin"), "reason", "maintenance", "durationMinutes", 10),
            ALERTS_ADMIN_ROLE);
        assertThat(silenced.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> escalated = adminApiClient().post(
            "/admin/alerts/" + alertId + "/escalate",
            Map.of("requestedBy", actor("Alerts Admin"), "policyId", "pagerduty", "reason", "sev1"),
            ALERTS_ADMIN_ROLE);
        assertThat(escalated.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> unsilenced = adminApiClient().post(
            "/admin/alerts/" + alertId + "/unsilence",
            Map.of("requestedBy", actor("Alerts Admin"), "reason", "resolved"),
            ALERTS_ADMIN_ROLE);
        assertThat(unsilenced.getStatusCode().value()).isEqualTo(200);
    }
}
