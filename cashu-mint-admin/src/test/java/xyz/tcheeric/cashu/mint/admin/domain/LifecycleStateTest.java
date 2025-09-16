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
}
