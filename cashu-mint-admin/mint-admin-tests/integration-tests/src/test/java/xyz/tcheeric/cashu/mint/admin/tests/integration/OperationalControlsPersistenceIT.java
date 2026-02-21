package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
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
class OperationalControlsPersistenceIT extends AbstractAdminIntegrationIT {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String MINT_ID = "55555555-5555-5555-5555-555555555555";

    // Verifies maintenance lifecycle plus rotate/force-close controls persist in operational_controls.
    @Test
    @Order(1)
    void shouldPersistOperationalControlRecords() {
        final ResponseEntity<JsonNode> scheduled = adminApiClient().post(
            "/admin/operations/mints/" + MINT_ID + "/maintenance/schedule",
            maintenancePayload("planned maintenance", 60),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(scheduled.getStatusCode().value()).isEqualTo(200);
        assertThat(scheduled.getBody().path("status").asText()).isEqualTo("SCHEDULED");

        final ResponseEntity<JsonNode> started = adminApiClient().post(
            "/admin/operations/mints/" + MINT_ID + "/maintenance/start",
            maintenancePayload("start maintenance", 60),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        assertThat(started.getBody().path("status").asText()).isEqualTo("IN_PROGRESS");

        final ResponseEntity<JsonNode> rotated = adminApiClient().post(
            "/admin/operations/mints/" + MINT_ID + "/keys/rotate",
            maintenancePayload("rotate keys", 5),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        assertThat(rotated.getBody().path("status").asText()).isEqualTo("KEY_ROTATION_INITIATED");

        final ResponseEntity<JsonNode> forceClosed = adminApiClient().post(
            "/admin/operations/mints/" + MINT_ID + "/force-close",
            maintenancePayload("force close", 1),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(forceClosed.getStatusCode().value()).isEqualTo(200);
        assertThat(forceClosed.getBody().path("status").asText()).isEqualTo("FORCE_CLOSED");

        final Integer inProgressCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM operational_controls WHERE mint_id = ? AND control_type = 'MAINTENANCE' AND status = 'IN_PROGRESS'",
            Integer.class,
            UUID.fromString(MINT_ID));
        final Integer keyRotationCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM operational_controls WHERE mint_id = ? AND control_type = 'KEY_ROTATION'",
            Integer.class,
            UUID.fromString(MINT_ID));
        final Integer forceCloseCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM operational_controls WHERE mint_id = ? AND control_type = 'FORCE_CLOSE'",
            Integer.class,
            UUID.fromString(MINT_ID));
        assertThat(inProgressCount).isEqualTo(1);
        assertThat(keyRotationCount).isEqualTo(1);
        assertThat(forceCloseCount).isEqualTo(1);

        final String missingMintId = UUID.randomUUID().toString();
        final ResponseEntity<JsonNode> missingMaintenance = adminApiClient().post(
            "/admin/operations/mints/" + missingMintId + "/maintenance/complete",
            maintenancePayload("complete missing", 1),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(missingMaintenance.getStatusCode().value()).isEqualTo(404);
        assertThat(missingMaintenance.getBody().path("code").asText()).isEqualTo("maintenance_not_found");
    }

    // Confirms in-progress maintenance can be completed after context restart using persisted control state.
    @Test
    @Order(2)
    void shouldCompleteMaintenanceAfterContextRestart() {
        final ResponseEntity<JsonNode> completed = adminApiClient().post(
            "/admin/operations/mints/" + MINT_ID + "/maintenance/complete",
            maintenancePayload("complete maintenance", 5),
            ADMIN_TOKEN,
            OPS_ADMIN_ROLE);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody().path("status").asText()).isEqualTo("COMPLETED");

        final Integer completedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM operational_controls WHERE mint_id = ? AND control_type = 'MAINTENANCE' AND status = 'COMPLETED'",
            Integer.class,
            UUID.fromString(MINT_ID));
        assertThat(completedCount).isEqualTo(1);
    }

    private Map<String, Object> maintenancePayload(final String reason, final Integer durationMinutes) {
        return Map.of(
            "reason", reason,
            "durationMinutes", durationMinutes,
            "requestedBy", Map.of("id", OPERATOR_ID, "displayName", "Ops Admin"));
    }
}
