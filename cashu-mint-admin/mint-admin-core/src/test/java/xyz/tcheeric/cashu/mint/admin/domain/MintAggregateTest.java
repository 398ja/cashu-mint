package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MintAggregateTest {

    private static final MintId MINT_ID = MintId.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

    private static AuditMetadata metadata(final String action) {
        return new AuditMetadata("operator", action, Instant.now());
    }

    private static ConfigurationSet configuration(final long revision, final String value) {
        return new ConfigurationSet(ConfigurationRevisionId.of(revision), Map.of("threshold", value), metadata("config"));
    }

    private static OperatorAccount operator() {
        return new OperatorAccount(UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), "Operator",
            Set.of("ADMIN"), metadata("operator"));
    }

    private static NotificationPolicy policy() {
        return new NotificationPolicy(true, false, Duration.ofMinutes(5), metadata("policy"));
    }

    @Test
    // Ensures create initialises the aggregate in a provisioning state with matching audit trail metadata.
    void shouldCreateMintAggregateWithProvisioningState() {
        final AuditMetadata creationMetadata = metadata("create");

        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            creationMetadata);

        assertThat(aggregate.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONING);
        final AuditMetadata storedAudit = aggregate.auditMetadata();
        assertThat(storedAudit.actor()).isEqualTo(creationMetadata.actor());
        assertThat(storedAudit.action()).isEqualTo(creationMetadata.action());
        assertThat(storedAudit.timestamp()).isEqualTo(creationMetadata.timestamp());
        assertThat(storedAudit.lifecycleContext().configurationRevisionId())
            .isEqualTo(ConfigurationRevisionId.of(1));
        assertThat(storedAudit.lifecycleContext().notificationPolicySnapshot()).isNotNull();
        assertThat(aggregate.auditTrail().entries()).containsExactly(storedAudit);
    }

    @Test
    // Ensures activate transitions the state and appends audit metadata.
    void shouldActivateMintAggregate() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final MintAggregate provisioned = aggregate.markProvisioned(metadata("provisioned"));
        final AuditMetadata activationMetadata = metadata("activate");

        final MintAggregate activated = provisioned.activate(activationMetadata);

        assertThat(activated.lifecycleState().value()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(activated.auditTrail().entries()).hasSize(3);
        final AuditMetadata latest = activated.auditTrail().latestMetadata();
        assertThat(latest.actor()).isEqualTo(activationMetadata.actor());
        assertThat(latest.action()).isEqualTo(activationMetadata.action());
        assertThat(latest.timestamp()).isEqualTo(activationMetadata.timestamp());
        assertThat(latest.lifecycleContext().configurationRevisionId())
            .isEqualTo(aggregate.configurationSet().revisionId());
        assertThat(aggregate.auditTrail().entries()).hasSize(1);
    }

    @Test
    // Ensures markProvisioned transitions from PROVISIONING to PROVISIONED.
    void shouldMarkProvisioned() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));

        final MintAggregate provisioned = aggregate.markProvisioned(metadata("vault ready"));

        assertThat(provisioned.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONED);
        assertThat(provisioned.auditTrail().entries()).hasSize(2);
    }

    @Test
    // Ensures markProvisionFailed transitions from PROVISIONING to PROVISION_FAILED.
    void shouldMarkProvisionFailed() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));

        final MintAggregate failed = aggregate.markProvisionFailed(metadata("vault failed"));

        assertThat(failed.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISION_FAILED);
        assertThat(failed.auditTrail().entries()).hasSize(2);
    }

    @Test
    // Ensures retryProvisioning transitions from PROVISION_FAILED back to PROVISIONING.
    void shouldRetryProvisioning() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final MintAggregate failed = aggregate.markProvisionFailed(metadata("vault failed"));

        final MintAggregate retrying = failed.retryProvisioning(metadata("retry"));

        assertThat(retrying.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONING);
        assertThat(retrying.auditTrail().entries()).hasSize(3);
    }

    @Test
    // Ensures configuration updates require the revision to advance.
    void shouldThrowWhenUpdatingConfigurationWithNonAdvancingRevision() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final ConfigurationSet sameRevision = configuration(1, "200");

        assertThrows(IllegalArgumentException.class, () -> aggregate.updateConfiguration(sameRevision, metadata("update")));
    }

    @Test
    // Ensures configuration updates succeed when the revision advances.
    void shouldUpdateConfigurationWhenRevisionAdvances() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final ConfigurationSet nextRevision = configuration(2, "200");
        final AuditMetadata updateMetadata = metadata("update");

        final MintAggregate updated = aggregate.updateConfiguration(nextRevision, updateMetadata);

        assertThat(updated.configurationSet()).isEqualTo(nextRevision);
        assertThat(updated.auditMetadata().actor()).isEqualTo(updateMetadata.actor());
        assertThat(updated.auditMetadata().lifecycleContext().configurationRevisionId())
            .isEqualTo(nextRevision.revisionId());
        assertThat(updated.auditTrail().entries()).hasSize(2);
    }

    @Test
    // Ensures lifecycle approval metadata is exposed to callers.
    void shouldExposeLifecycleApprovalMetadata() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final MintAggregate provisioned = aggregate.markProvisioned(metadata("provisioned"));

        final LifecycleState.TransitionApproval approval = provisioned
            .approvalRequirementsFor(LifecycleState.State.ACTIVE)
            .orElseThrow();

        assertThat(approval.requiredSignoffs()).contains("Operations");
        assertThat(approval.description()).contains("Activation");
    }

    @Test
    // Ensures terminal states reject further transitions with descriptive context.
    void shouldRejectTransitionsFromTerminalState() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final MintAggregate decommissioned = aggregate.decommission(metadata("decommission"));

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> decommissioned.activate(metadata("activate")));

        assertThat(exception).hasMessageContaining("DECOMMISSIONED");
        assertThat(exception.getMessage()).contains("State is terminal");
    }

    @Test
    // Ensures reconstitution rejects inconsistent audit metadata.
    void shouldRejectReconstitutionWhenAuditMetadataDoesNotMatchLatest() {
        final AuditMetadata initial = metadata("initial");
        final AuditMetadata later = metadata("later");
        final AuditTrail trail = AuditTrail.create(initial).append(later);

        assertThrows(IllegalArgumentException.class,
            () -> MintAggregate.reconstitute(MINT_ID, LifecycleState.provisioned(), configuration(1, "100"), operator(),
                policy(), trail, initial));
    }
}
