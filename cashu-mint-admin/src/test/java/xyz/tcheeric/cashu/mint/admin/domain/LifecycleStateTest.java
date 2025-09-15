package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LifecycleStateTest {

    // Ensures a provisioned lifecycle can become active when requested.
    @Test
    void shouldTransitionFromProvisionedToActive() {
        final LifecycleState provisioned = LifecycleState.provisioned();

        final LifecycleState active = provisioned.transitionTo(LifecycleState.State.ACTIVE);

        assertThat(active.value()).isEqualTo(LifecycleState.State.ACTIVE);
        assertThat(provisioned.value()).isEqualTo(LifecycleState.State.PROVISIONED);
    }

    // Ensures illegal transitions raise an exception to protect invariants.
    @Test
    void shouldRejectTransitionFromDecommissionedToActive() {
        final LifecycleState decommissioned = LifecycleState.of(LifecycleState.State.DECOMMISSIONED);

        assertThatThrownBy(() -> decommissioned.transitionTo(LifecycleState.State.ACTIVE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot transition");
    }
}
