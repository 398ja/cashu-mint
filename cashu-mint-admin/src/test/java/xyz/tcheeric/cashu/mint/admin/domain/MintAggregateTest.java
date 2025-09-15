package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MintAggregateTest {

    // Ensures activating a mint advances the lifecycle and records audit metadata.
    @Test
    void shouldActivateMintAndRecordAuditMetadata() {
        final MintAggregate provisioned = newAggregate();
        final AuditMetadata activation = new AuditMetadata("operator", "activate", Instant.parse("2024-01-02T00:00:00Z"));

        final MintAggregate activated = provisioned.activate(activation);

        assertThat(activated.lifecycleState().value()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(activated.auditTrail().entries()).hasSize(2);
        assertThat(activated.auditTrail().latestMetadata()).isEqualTo(activation);
        assertThat(activated.auditMetadata()).isEqualTo(activation);
        assertThat(provisioned.lifecycleState().value()).isEqualTo(LifecycleState.State.PROVISIONED);
        assertThat(provisioned.auditTrail().entries()).hasSize(1);
    }

    // Ensures configuration updates must increase the revision to preserve ordering.
    @Test
    void shouldRejectConfigurationWithStaleRevision() {
        final MintAggregate aggregate = newAggregate();
        final ConfigurationSet staleConfiguration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("max_tokens", "1000"),
            new AuditMetadata("operator", "stale-config", Instant.parse("2024-01-03T00:00:00Z")));

        final AuditMetadata audit = new AuditMetadata("operator", "config-update", Instant.parse("2024-01-04T00:00:00Z"));

        assertThatThrownBy(() -> aggregate.updateConfiguration(staleConfiguration, audit))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("revision must advance");
    }

    private MintAggregate newAggregate() {
        final MintId mintId = MintId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("max_tokens", "500"),
            new AuditMetadata("operator", "seed-config", Instant.parse("2024-01-01T00:00:00Z")));
        final OperatorAccount operator = new OperatorAccount(UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "Primary Operator",
            Set.of("ADMIN"),
            new AuditMetadata("system", "operator-created", Instant.parse("2024-01-01T01:00:00Z")));
        final NotificationPolicy policy = new NotificationPolicy(true, true, Duration.ofMinutes(5),
            new AuditMetadata("system", "policy-created", Instant.parse("2024-01-01T02:00:00Z")));
        final AuditMetadata creation = new AuditMetadata("system", "mint-created", Instant.parse("2024-01-01T03:00:00Z"));
        return MintAggregate.create(mintId, configuration, operator, policy, creation);
    }
}
