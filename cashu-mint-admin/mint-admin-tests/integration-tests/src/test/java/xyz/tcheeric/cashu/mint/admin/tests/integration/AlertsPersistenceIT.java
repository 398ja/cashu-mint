package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.AbstractAdminIntegrationIT;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AlertsPersistenceIT extends AbstractAdminIntegrationIT {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String ALERT_ID = "alert-persistence-001";

    // Verifies full alert lifecycle commands persist in admin_alerts and escalation rows.
    @Test
    @Order(1)
    void shouldPersistAlertLifecycleAndEscalations() {
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/alerts",
            Map.of(
                "alertId", ALERT_ID,
                "mintId", "mint-1",
                "severity", "CRITICAL",
                "summary", "Mint offline",
                "labels", Map.of("region", "us-east"),
                "requestedBy", actor()),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> acknowledged = adminApiClient().post(
            "/admin/alerts/" + ALERT_ID + "/acknowledge",
            Map.of("requestedBy", actor(), "reason", "ack"),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(acknowledged.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> silenced = adminApiClient().post(
            "/admin/alerts/" + ALERT_ID + "/silence",
            Map.of("requestedBy", actor(), "reason", "maintenance", "durationMinutes", 15),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(silenced.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> unsilenced = adminApiClient().post(
            "/admin/alerts/" + ALERT_ID + "/unsilence",
            Map.of("requestedBy", actor(), "reason", "clear"),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(unsilenced.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> escalated = adminApiClient().post(
            "/admin/alerts/" + ALERT_ID + "/escalate",
            Map.of("requestedBy", actor(), "policyId", "pagerduty", "reason", "sev1"),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(escalated.getStatusCode().value()).isEqualTo(200);
        assertThat(escalated.getBody().path("escalations").toString()).contains("pagerduty");

        final Boolean acknowledgedFlag = jdbcTemplate.queryForObject(
            "SELECT acknowledged FROM admin_alerts WHERE alert_id = ?",
            Boolean.class,
            ALERT_ID);
        final Boolean silencedFlag = jdbcTemplate.queryForObject(
            "SELECT silenced FROM admin_alerts WHERE alert_id = ?",
            Boolean.class,
            ALERT_ID);
        final Integer escalationCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_alert_escalations WHERE alert_id = ?",
            Integer.class,
            ALERT_ID);
        assertThat(acknowledgedFlag).isTrue();
        assertThat(silencedFlag).isFalse();
        assertThat(escalationCount).isEqualTo(1);

        final ResponseEntity<JsonNode> duplicate = adminApiClient().post(
            "/admin/alerts",
            Map.of(
                "alertId", ALERT_ID,
                "mintId", "mint-1",
                "severity", "CRITICAL",
                "summary", "Duplicate",
                "requestedBy", actor()),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().path("code").asText()).isEqualTo("alert_exists");
    }

    // Confirms alert state remains available after context restart and allows additional escalation.
    @Test
    @Order(2)
    void shouldRetainAlertStateAcrossContextRestart() {
        final ResponseEntity<JsonNode> escalated = adminApiClient().post(
            "/admin/alerts/" + ALERT_ID + "/escalate",
            Map.of("requestedBy", actor(), "policyId", "slack", "reason", "notify channel"),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);

        assertThat(escalated.getStatusCode().value()).isEqualTo(200);
        final Integer escalationCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_alert_escalations WHERE alert_id = ?",
            Integer.class,
            ALERT_ID);
        assertThat(escalationCount).isEqualTo(2);
    }

    // Ensures unknown alert operations map to the not-found API contract.
    @Test
    @Order(3)
    void shouldReturnNotFoundForUnknownAlert() {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/alerts/missing-alert/acknowledge",
            Map.of("requestedBy", actor(), "reason", "missing"),
            ADMIN_TOKEN,
            ALERTS_ADMIN_ROLE);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("code").asText()).isEqualTo("alert_not_found");
    }

    private Map<String, Object> actor() {
        return Map.of("id", OPERATOR_ID, "displayName", "Alerts Admin");
    }
}
