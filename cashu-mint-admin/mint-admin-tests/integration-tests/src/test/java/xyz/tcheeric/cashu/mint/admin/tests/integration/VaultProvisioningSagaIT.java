package xyz.tcheeric.cashu.mint.admin.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxDispatcher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.AbstractAdminIntegrationIT;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.StubVaultProvisioningPort;

@Sql(scripts = "classpath:sql/truncate_admin_tables.sql", executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Import(VaultProvisioningSagaIT.SagaTestConfig.class)
@TestPropertySource(properties = {
    "admin.outbox.enabled=true",
    "admin.outbox.poll.interval=999999999",
    "admin.vault.provision.max-retries=2",
    "admin.outbox.failure.backoff=PT1S"
})
class VaultProvisioningSagaIT extends AbstractAdminIntegrationIT {

    @TestConfiguration
    static class SagaTestConfig {

        @Bean
        @Primary
        VaultProvisioningPort testVaultProvisioningPort() {
            return new StubVaultProvisioningPort();
        }
    }

    @Autowired
    private LifecycleEventOutboxDispatcher outboxDispatcher;

    @Autowired
    private VaultProvisioningPort vaultProvisioningPort;

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    @BeforeEach
    void resetStub() {
        stub().reset();
    }

    // Verifies that creating a mint starts in PROVISIONING and dispatching the outbox transitions it to PROVISIONED.
    @Test
    void shouldTransitionToProvisionedAfterCreate() {
        final String mintId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        assertMintState(mintId, "PROVISIONING");

        outboxDispatcher.dispatchPending(100);

        assertMintState(mintId, "PROVISIONED");
        assertThat(stub().countInvocations("provision")).isEqualTo(1);
    }

    // Verifies that after max retries the mint transitions to PROVISION_FAILED.
    @Test
    void shouldTransitionToProvisionFailedAfterMaxRetries() {
        final String mintId = UUID.randomUUID().toString();
        stub().setShouldFail(true);

        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);

        // First dispatch: attempt 1 fails, schedules retry with backoff
        outboxDispatcher.dispatchPending(100);
        assertMintState(mintId, "PROVISIONING");

        // Reset available_at so the message is picked up again without waiting for backoff
        resetOutboxAvailability(mintId);

        // Second dispatch: attempt 2 (>= maxRetries=2), triggers compensate and PROVISION_FAILED
        outboxDispatcher.dispatchPending(100);
        assertMintState(mintId, "PROVISION_FAILED");
        assertThat(stub().countInvocations("compensate")).isEqualTo(1);
    }

    // Verifies the full lifecycle flow after vault provisioning completes.
    @Test
    void shouldAllowFullLifecycleAfterProvisioning() {
        final String mintId = UUID.randomUUID().toString();

        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        outboxDispatcher.dispatchPending(100);
        assertMintState(mintId, "PROVISIONED");

        // PROVISIONED -> ACTIVE
        final ResponseEntity<JsonNode> activated = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor(), "reason", "Activate after provisioning"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(activated.getStatusCode().value()).isEqualTo(200);
        assertMintState(mintId, "ACTIVE");

        // ACTIVE -> SUSPENDED
        final ResponseEntity<JsonNode> paused = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/pause",
            Map.of("requestedBy", actor(), "reason", "Maintenance"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(paused.getStatusCode().value()).isEqualTo(200);
        assertMintState(mintId, "SUSPENDED");

        // SUSPENDED -> ACTIVE
        final ResponseEntity<JsonNode> resumed = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor(), "reason", "Maintenance complete"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(resumed.getStatusCode().value()).isEqualTo(200);
        assertMintState(mintId, "ACTIVE");

        // ACTIVE -> DECOMMISSIONED
        final ResponseEntity<JsonNode> retired = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/retire",
            Map.of("requestedBy", actor(), "reason", "Retire"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(retired.getStatusCode().value()).isEqualTo(200);
        assertMintState(mintId, "DECOMMISSIONED");
    }

    // Verifies that configuration parameters are stored in configuration_revisions at creation time.
    @Test
    void shouldStoreConfigurationParametersAtCreation() {
        final String mintId = UUID.randomUUID().toString();

        adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", mintId,
                "requestedBy", actor(),
                "metadata", Map.of("displayName", "Config Mint"),
                "configuration", Map.of(
                    "versionTag", "v1",
                    "name", "config-mint",
                    "parameters", Map.of("cashu.unit", "usd", "cashu.denominations", "1,2,4,8"))),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);

        final Integer revisionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM configuration_revisions WHERE mint_id = ?",
            Integer.class,
            UUID.fromString(mintId));
        assertThat(revisionCount).isGreaterThanOrEqualTo(1);
    }

    // Verifies that resume is rejected while the mint is still in PROVISIONING state.
    @Test
    void shouldRejectResumeWhileStillProvisioning() {
        final String mintId = UUID.randomUUID().toString();

        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);

        // Attempt resume without dispatching outbox (state is still PROVISIONING)
        final ResponseEntity<JsonNode> resumed = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintId + "/resume",
            Map.of("requestedBy", actor(), "reason", "Premature activation"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(resumed.getStatusCode().value()).isEqualTo(409);
    }

    // Verifies that compensate is called when provisioning permanently fails.
    @Test
    void shouldCallCompensateOnPermanentFailure() {
        final String mintId = UUID.randomUUID().toString();
        stub().setShouldFail(true);

        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayload(mintId),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);

        // Exhaust retries (maxRetries=2)
        outboxDispatcher.dispatchPending(100);
        resetOutboxAvailability(mintId);
        outboxDispatcher.dispatchPending(100);

        assertThat(stub().countInvocations("compensate")).isEqualTo(1);
        assertThat(stub().countInvocations("provision")).isEqualTo(2);
    }

    // Verifies that a second mint cannot be activated for the same unit when one is already active.
    @Test
    void shouldRejectSecondActiveMintForSameUnit() {
        final String mintIdA = UUID.randomUUID().toString();
        final String mintIdB = UUID.randomUUID().toString();

        // Create mint A with unit=sat (no other sat mint exists yet)
        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayloadWithUnit(mintIdA, "sat"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        outboxDispatcher.dispatchPending(100);
        assertMintState(mintIdA, "PROVISIONED");

        // Activate mint A
        final ResponseEntity<JsonNode> activatedA = adminApiClient().post(
            "/admin/lifecycle/mints/" + mintIdA + "/resume",
            Map.of("requestedBy", actor(), "reason", "Activate A"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(activatedA.getStatusCode().value()).isEqualTo(200);
        assertMintState(mintIdA, "ACTIVE");

        // Attempt to create mint B with same unit=sat → should fail 409 (non-terminal mint exists)
        final ResponseEntity<JsonNode> createdB = adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayloadWithUnit(mintIdB, "sat"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(createdB.getStatusCode().value()).isEqualTo(409);
    }

    // Verifies that creating a mint with a different unit succeeds even when another unit is active.
    @Test
    void shouldAllowCreateForDifferentUnit() {
        final String mintIdA = UUID.randomUUID().toString();
        final String mintIdB = UUID.randomUUID().toString();

        // Create and activate mint A with unit=sat
        adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayloadWithUnit(mintIdA, "sat"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        outboxDispatcher.dispatchPending(100);
        adminApiClient().post(
            "/admin/lifecycle/mints/" + mintIdA + "/resume",
            Map.of("requestedBy", actor(), "reason", "Activate A"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertMintState(mintIdA, "ACTIVE");

        // Create mint B with unit=usd → should succeed
        final ResponseEntity<JsonNode> createdB = adminApiClient().post(
            "/admin/lifecycle/mints",
            createMintPayloadWithUnit(mintIdB, "usd"),
            ADMIN_TOKEN,
            MINT_ADMIN_ROLE);
        assertThat(createdB.getStatusCode().value()).isEqualTo(200);
    }

    private StubVaultProvisioningPort stub() {
        return (StubVaultProvisioningPort) vaultProvisioningPort;
    }

    private void assertMintState(final String mintId, final String expectedState) {
        final String state = jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM mints WHERE mint_id = ?",
            String.class,
            UUID.fromString(mintId));
        assertThat(state).isEqualTo(expectedState);
    }

    private void resetOutboxAvailability(final String mintId) {
        jdbcTemplate.update(
            "UPDATE admin_outbox SET available_at = NOW() - INTERVAL '1 second' WHERE aggregate_id = ? AND dispatched_at IS NULL",
            UUID.fromString(mintId));
    }

    private Map<String, Object> createMintPayloadWithUnit(final String mintId, final String unit) {
        return Map.of(
            "mintId", mintId,
            "requestedBy", actor(),
            "metadata", Map.of(
                "displayName", "Unit Mint " + mintId.substring(0, 8),
                "description", "Unit enforcement mint",
                "tags", List.of("unit-test")),
            "configuration", Map.of(
                "versionTag", "v1",
                "name", "unit-mint",
                "cashu.unit", unit));
    }

    private Map<String, Object> createMintPayload(final String mintId) {
        return Map.of(
            "mintId", mintId,
            "requestedBy", actor(),
            "metadata", Map.of(
                "displayName", "Saga Mint " + mintId.substring(0, 8),
                "description", "Saga integration mint",
                "tags", List.of("saga")),
            "configuration", Map.of(
                "versionTag", "v1",
                "name", "saga-mint"));
    }

    private Map<String, Object> actor() {
        return Map.of("id", OPERATOR_ID, "displayName", "Saga Operator");
    }
}
