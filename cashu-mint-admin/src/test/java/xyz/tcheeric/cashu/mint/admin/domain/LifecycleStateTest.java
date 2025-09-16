package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LifecycleStateTest {

    @Test
    // Ensures allowed transitions succeed and produce new state instances.
    void shouldAllowValidTransition() {
        final LifecycleState provisioned = LifecycleState.provisioned();

        final LifecycleState active = provisioned.transitionTo(LifecycleState.State.ACTIVE);

        assertThat(active.value()).isEqualTo(LifecycleState.State.ACTIVE);
    }

    @Test
    // Ensures transitioning to the same state returns the existing instance.
    void shouldReturnSameInstanceWhenTransitioningToSameState() {
        final LifecycleState provisioned = LifecycleState.provisioned();

        final LifecycleState same = provisioned.transitionTo(LifecycleState.State.PROVISIONED);

        assertThat(same).isSameAs(provisioned);
    }

    @Test
    // Ensures invalid transitions are rejected with an exception.
    void shouldRejectInvalidTransition() {
        final LifecycleState provisioned = LifecycleState.provisioned();

        assertThrows(IllegalStateException.class,
            () -> provisioned.transitionTo(LifecycleState.State.SUSPENDED));
    }
}
