package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.AbstractAdminIntegrationIT;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class LifecyclePersistenceIT extends AbstractAdminIntegrationIT {

    @Autowired
    private MintRepository mintRepository;

    @Autowired
    private MintLifecycleEventPublisher eventPublisher;

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    // Verifies lifecycle transitions and outbox/history creation in PostgreSQL.
    @Test
    void shouldPersistLifecycleWorkflow() {
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        // The aggregate's initial ConfigurationSet is persisted at creation, even
        // though configuration governance endpoints no longer exist.
        final Integer revisionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM configuration_revisions WHERE mint_id = ?",
            Integer.class,
            UUID.fromString(mintId));
        assertThat(revisionCount).isGreaterThanOrEqualTo(1);

        // Advance from PROVISIONING to PROVISIONED (simulates vault provisioning completion)
        final MintId mint = MintId.fromString(mintId);
        final MintAggregate provisioning = mintRepository.findById(mint)
            .orElseThrow(() -> new IllegalStateException("mint not found: " + mintId));
        final AuditMetadata provisionAudit = new AuditMetadata("system", "Vault provisioned", Instant.now());
        final MintAggregate provisioned = provisioning.markProvisioned(provisionAudit);
        mintRepository.save(provisioned);
        eventPublisher.publish(MintLifecycleEvent.vaultProvisioned(mint,
            provisioning.lifecycleState().value(), provisioned.lifecycleState().value(),
            provisioned.configurationSet().revisionId(), "v1", provisionAudit));

        final ResponseEntity<JsonNode> activated = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor(), "reason", "Activate after provisioning"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(activated.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> paused = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/pause",
            Map.of("requestedBy", actor(), "reason", "Maintenance"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(paused.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> resumed = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor(), "reason", "Maintenance completed"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(resumed.getStatusCode().value()).isEqualTo(200);

        final ResponseEntity<JsonNode> retired = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/retire",
            Map.of("requestedBy", actor(), "reason", "Retire"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(retired.getStatusCode().value()).isEqualTo(200);

        final String lifecycleState = jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM mints WHERE mint_id = ?",
            String.class,
            UUID.fromString(mintId));
        assertThat(lifecycleState).isEqualTo("DECOMMISSIONED");

        final Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_outbox WHERE aggregate_id = ?",
            Integer.class,
            UUID.fromString(mintId));
        assertThat(outboxCount).isGreaterThan(0);

        final List<String> states = jdbcTemplate.queryForList(
            "SELECT payload::json->>'currentState' FROM admin_outbox WHERE aggregate_id = ? ORDER BY occurred_at, event_id",
            String.class,
            UUID.fromString(mintId));
        assertThat(states).contains("PROVISIONING", "PROVISIONED", "ACTIVE", "SUSPENDED", "ACTIVE", "DECOMMISSIONED");
    }

    private Map<String, Object> createMintPayload(final String mintId) {
        return Map.of(
            "mintId", mintId,
            "requestedBy", actor(),
            "metadata", Map.of(
                "displayName", "Mint " + mintId.substring(0, 8),
                "description", "Integration mint",
                "tags", List.of("integration")),
            "configuration", Map.of(
                "versionTag", "v1",
                "name", "integration-mint"));
    }

    private Map<String, Object> actor() {
        return Map.of("id", OPERATOR_ID, "displayName", "Integration Operator");
    }
}
