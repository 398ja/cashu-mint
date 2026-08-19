package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class MintProvisioningE2EIT extends AbstractAdminE2EIT {

    // Verifies mint provisioning succeeds against the live compose stack.
    @Test
    void shouldProvisionMint() {
        final String mintId = UUID.randomUUID().toString();

        // Create mint (starts in PROVISIONING state).
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            MINT_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getBody().path("mintId").asText()).isEqualTo(mintId);

        // Wait for vault provisioning saga to complete (PROVISIONING -> PROVISIONED).
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> {
                final ResponseEntity<JsonNode> detail = adminApiClient().get(
                    "/admin/lifecycle/mints/" + mintId,
                    MINT_ADMIN_ROLE);
                assertThat(detail.getStatusCode().value()).isEqualTo(200);
                assertThat(detail.getBody().path("lifecycleState").asText()).isEqualTo("PROVISIONED");
            });

        final ResponseEntity<JsonNode> mintInfo = mintApiClient().get("/v1/info");
        assertThat(mintInfo.getStatusCode().value()).isEqualTo(200);
    }

    private Map<String, Object> createMintPayload(final String mintId) {
        return Map.of(
            "mintId", mintId,
            "requestedBy", actor("E2E Mint Admin"),
            "metadata", Map.of(
                "displayName", "E2E Mint " + mintId.substring(0, 8),
                "description", "Provisioned during e2e tests",
                "tags", List.of("e2e", "mint-provisioning")),
            "configuration", Map.of(
                "versionTag", "e2e-v1",
                "name", "e2e-mint"));
    }
}
