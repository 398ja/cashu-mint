package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LifecycleStateTest {

    @Test
    // Ensures guardTransitionTo provides approval metadata for valid transitions.
    void shouldProvideApprovalMetadataForValidTransition() {
        final LifecycleState state = LifecycleState.provisioned();

        final LifecycleState.TransitionApproval approval = state.guardTransitionTo(LifecycleState.State.ACTIVE);

        assertThat(approval.target()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(approval.requiredSignoffs()).containsExactlyInAnyOrder("Operations", "Security");
        assertThat(approval.description()).contains("Activation");
    }

    @Test
    // Ensures guardTransitionTo describes why invalid transitions fail.
    void shouldDescribeInvalidTransitionAttempt() {
        final LifecycleState state = LifecycleState.provisioned();

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> state.guardTransitionTo(LifecycleState.State.SUSPENDED));

        assertThat(exception).hasMessageContaining("Cannot transition from PROVISIONED to SUSPENDED");
        assertThat(exception).hasMessageContaining("Allowed transitions");
        assertThat(exception.getMessage()).contains("ACTIVE (requires sign-off from: Operations, Security");
    }

    @Test
    // Ensures approval metadata is unavailable for disallowed transitions.
    void shouldReturnEmptyApprovalMetadataWhenTransitionNotAllowed() {
        final LifecycleState state = LifecycleState.provisioned();

        assertThat(state.approvalRequirementsFor(LifecycleState.State.SUSPENDED)).isEmpty();
    }

    @Test
    // Ensures PROVISIONING can transition to PROVISIONED on successful vault provisioning.
    void shouldAllowProvisioningToProvisioned() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISIONING);

        final LifecycleState next = state.transitionTo(LifecycleState.State.PROVISIONED);

        assertThat(next.value()).isEqualTo(LifecycleState.State.PROVISIONED);
    }

    @Test
    // Ensures PROVISIONING can transition to PROVISION_FAILED on permanent failure.
    void shouldAllowProvisioningToProvisionFailed() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISIONING);

        final LifecycleState next = state.transitionTo(LifecycleState.State.PROVISION_FAILED);

        assertThat(next.value()).isEqualTo(LifecycleState.State.PROVISION_FAILED);
    }

    @Test
    // Ensures PROVISIONING can transition to DECOMMISSIONED for operator abort.
    void shouldAllowProvisioningToDecommissioned() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISIONING);

        final LifecycleState next = state.transitionTo(LifecycleState.State.DECOMMISSIONED);

        assertThat(next.value()).isEqualTo(LifecycleState.State.DECOMMISSIONED);
    }

    @Test
    // Ensures PROVISIONING rejects transition directly to ACTIVE.
    void shouldRejectProvisioningToActive() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISIONING);

        assertThrows(IllegalStateException.class, () -> state.transitionTo(LifecycleState.State.ACTIVE));
    }

    @Test
    // Ensures PROVISION_FAILED can retry by transitioning back to PROVISIONING.
    void shouldAllowProvisionFailedToProvisioning() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISION_FAILED);

        final LifecycleState next = state.transitionTo(LifecycleState.State.PROVISIONING);

        assertThat(next.value()).isEqualTo(LifecycleState.State.PROVISIONING);
    }

    @Test
    // Ensures PROVISION_FAILED can be decommissioned.
    void shouldAllowProvisionFailedToDecommissioned() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISION_FAILED);

        final LifecycleState next = state.transitionTo(LifecycleState.State.DECOMMISSIONED);

        assertThat(next.value()).isEqualTo(LifecycleState.State.DECOMMISSIONED);
    }

    @Test
    // Ensures PROVISION_FAILED rejects transition directly to ACTIVE.
    void shouldRejectProvisionFailedToActive() {
        final LifecycleState state = LifecycleState.of(LifecycleState.State.PROVISION_FAILED);

        assertThrows(IllegalStateException.class, () -> state.transitionTo(LifecycleState.State.ACTIVE));
    }
}
