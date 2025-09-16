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
    // Ensures create initialises the aggregate in a provisioned state with matching audit trail metadata.
    void shouldCreateMintAggregateWithProvisionedState() {
        final AuditMetadata creationMetadata = metadata("create");

        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            creationMetadata);

        assertThat(aggregate.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONED);
        assertThat(aggregate.auditMetadata()).isEqualTo(creationMetadata);
        assertThat(aggregate.auditTrail().entries()).containsExactly(creationMetadata);
    }

    @Test
    // Ensures activate transitions the state and appends audit metadata.
    void shouldActivateMintAggregate() {
        final MintAggregate aggregate = MintAggregate.create(MINT_ID, configuration(1, "100"), operator(), policy(),
            metadata("create"));
        final AuditMetadata activationMetadata = metadata("activate");

        final MintAggregate activated = aggregate.activate(activationMetadata);

        assertThat(activated.lifecycleState().value()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(activated.auditTrail().entries()).hasSize(2).contains(activationMetadata);
        assertThat(aggregate.auditTrail().entries()).hasSize(1);
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
        assertThat(updated.auditMetadata()).isEqualTo(updateMetadata);
        assertThat(updated.auditTrail().entries()).hasSize(2);
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
