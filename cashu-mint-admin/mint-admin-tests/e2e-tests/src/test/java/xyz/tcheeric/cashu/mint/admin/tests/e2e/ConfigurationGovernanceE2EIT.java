package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class ConfigurationGovernanceE2EIT extends AbstractAdminE2EIT {

    // Verifies apply, preview, and rollback configuration governance APIs execute successfully.
    @Test
    void shouldExecuteConfigurationGovernanceFlow() {
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", mintId,
                "requestedBy", actor("Config Admin"),
                "metadata", Map.of("displayName", "Config Mint"),
                "configuration", Map.of("versionTag", "cfg-v1")),
            MINT_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> firstApply = adminApiClient().post(
            "/admin/configuration/mints/" + mintId + "/apply",
            Map.of(
                "requestedBy", actor("Config Admin"),
                "proposedConfiguration", Map.of("fee_ppm", "100"),
                "changeSummary", "initial configuration"),
            MINT_ADMIN_ROLE);
        assertThat(firstApply.getStatusCode().value()).isEqualTo(200);
        final String firstRevision = firstApply.getBody().path("revisionId").asText("1");

        final ResponseEntity<JsonNode> preview = adminApiClient().post(
            "/admin/configuration/mints/" + mintId + "/preview",
            Map.of(
                "requestedBy", actor("Config Admin"),
                "proposedConfiguration", Map.of("fee_ppm", "150"),
                "baseRevisionId", firstRevision),
            MINT_ADMIN_ROLE);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> secondApply = adminApiClient().post(
            "/admin/configuration/mints/" + mintId + "/apply",
            Map.of(
                "requestedBy", actor("Config Admin"),
                "proposedConfiguration", Map.of("fee_ppm", "150"),
                "changeSummary", "raise fee"),
            MINT_ADMIN_ROLE);
        assertThat(secondApply.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> rollback = adminApiClient().post(
            "/admin/configuration/mints/" + mintId + "/rollback",
            Map.of(
                "requestedBy", actor("Config Admin"),
                "targetRevisionId", firstRevision,
                "reason", "rollback to baseline"),
            MINT_ADMIN_ROLE);
        assertThat(rollback.getStatusCode().value()).isEqualTo(200);
    }
}
