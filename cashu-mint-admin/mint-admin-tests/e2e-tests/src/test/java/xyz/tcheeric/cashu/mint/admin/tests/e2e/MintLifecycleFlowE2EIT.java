package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class MintLifecycleFlowE2EIT extends AbstractAdminE2EIT {

    // Verifies create, activate, pause, resume, and retire lifecycle transitions with the mint API still reachable.
    @Test
    void shouldExecuteLifecycleTransitionsAgainstRunningStack() {
        final String mintId = UUID.randomUUID().toString();

        // Create a new mint (starts in PROVISIONED state).
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", mintId,
                "requestedBy", actor("Lifecycle Admin"),
                "metadata", Map.of("displayName", "Lifecycle Mint"),
                "configuration", Map.of("versionTag", "lifecycle-v1")),
            MINT_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        // Activate the mint (PROVISIONED -> ACTIVE) before it can be paused.
        final ResponseEntity<JsonNode> activated = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor("Lifecycle Admin"), "reason", "activate for e2e"),
            MINT_ADMIN_ROLE);
        assertThat(activated.getStatusCode().value()).isEqualTo(200);
        assertThat(activated.getBody().path("currentState").asText()).isEqualTo("ACTIVE");

        // Pause the active mint (ACTIVE -> SUSPENDED).
        final ResponseEntity<JsonNode> paused = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/pause",
            Map.of("requestedBy", actor("Lifecycle Admin"), "reason", "pause for e2e"),
            MINT_ADMIN_ROLE);
        assertThat(paused.getStatusCode().value()).isEqualTo(200);
        assertThat(paused.getBody().path("currentState").asText()).isEqualTo("SUSPENDED");

        // Resume the suspended mint (SUSPENDED -> ACTIVE).
        final ResponseEntity<JsonNode> resumed = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor("Lifecycle Admin"), "reason", "resume for e2e"),
            MINT_ADMIN_ROLE);
        assertThat(resumed.getStatusCode().value()).isEqualTo(200);
        assertThat(resumed.getBody().path("currentState").asText()).isEqualTo("ACTIVE");

        // Retire the mint (ACTIVE -> DECOMMISSIONED).
        final ResponseEntity<JsonNode> retired = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/retire",
            Map.of("requestedBy", actor("Lifecycle Admin"), "reason", "retire for e2e"),
            MINT_ADMIN_ROLE);
        assertThat(retired.getStatusCode().value()).isEqualTo(200);
        assertThat(retired.getBody().path("currentState").asText()).isEqualTo("DECOMMISSIONED");

        // Verify the mint REST API is still reachable after lifecycle operations.
        final ResponseEntity<JsonNode> mintInfo = mintApiClient().get("/v1/info");
        assertThat(mintInfo.getStatusCode().value()).isEqualTo(200);
    }
}
