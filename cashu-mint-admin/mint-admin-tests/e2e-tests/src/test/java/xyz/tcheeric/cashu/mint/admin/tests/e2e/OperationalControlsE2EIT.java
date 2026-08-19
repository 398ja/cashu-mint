package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class OperationalControlsE2EIT extends AbstractAdminE2EIT {

    // Verifies maintenance and force-close operational controls through the admin API.
    @Test
    void shouldExecuteOperationalControlWorkflow() {
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> scheduled = adminApiClient().post(
            "/admin/operations/mints/" + mintId + "/maintenance/schedule",
            maintenancePayload("schedule maintenance", 30),
            OPS_ADMIN_ROLE);
        assertThat(scheduled.getStatusCode().value()).isEqualTo(200);
        assertThat(scheduled.getBody().path("status").asText()).isEqualTo("SCHEDULED");

        final ResponseEntity<JsonNode> started = adminApiClient().post(
            "/admin/operations/mints/" + mintId + "/maintenance/start",
            maintenancePayload("start maintenance", 30),
            OPS_ADMIN_ROLE);
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        assertThat(started.getBody().path("status").asText()).isEqualTo("IN_PROGRESS");

        final ResponseEntity<JsonNode> completed = adminApiClient().post(
            "/admin/operations/mints/" + mintId + "/maintenance/complete",
            maintenancePayload("complete maintenance", 30),
            OPS_ADMIN_ROLE);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody().path("status").asText()).isEqualTo("COMPLETED");

        final ResponseEntity<JsonNode> forceClosed = adminApiClient().post(
            "/admin/operations/mints/" + mintId + "/force-close",
            maintenancePayload("force close for safety", 1),
            OPS_ADMIN_ROLE);
        assertThat(forceClosed.getStatusCode().value()).isEqualTo(200);
        assertThat(forceClosed.getBody().path("status").asText()).isEqualTo("FORCE_CLOSED");
    }

    // Verifies rotation is accepted as durable work rather than answered inline.
    // What it then does to the mint's keysets is asserted in KeyRotationE2EIT,
    // against the mint itself; asserting the echoed status here is what let a
    // placeholder implementation pass this suite for so long.
    @Test
    void shouldAcceptKeyRotationAsDurableWork() {
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> rotated = adminApiClient().post(
            "/admin/operations/mints/" + mintId + "/keys/rotate",
            maintenancePayload("rotate signing keys", 5),
            OPS_ADMIN_ROLE);

        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        assertThat(rotated.getBody().path("message").asText())
            .as("rotation must no longer answer with a placeholder")
            .doesNotContain("placeholder");

        final ResponseEntity<JsonNode> controls = adminApiClient().get(
            "/admin/operations/mints/" + mintId + "/controls", OPS_ADMIN_ROLE);
        assertThat(controls.getStatusCode().value()).isEqualTo(200);
        assertThat(controls.getBody().toString())
            .as("the rotation must be recorded as an operational control")
            .contains("KEY_ROTATION");
    }

    private Map<String, Object> maintenancePayload(final String reason, final int durationMinutes) {
        return Map.of(
            "reason", reason,
            "durationMinutes", durationMinutes,
            "requestedBy", actor("Ops Admin"));
    }
}
