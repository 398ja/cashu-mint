package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Lifecycle state for a mint aggregate with guarded transitions.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class LifecycleState {

    public enum State {
        PROVISIONED,
        ACTIVE,
        SUSPENDED,
        DECOMMISSIONED
    }

    private static final Map<State, Set<State>> ALLOWED_TRANSITIONS = Map.of(
        State.PROVISIONED, Set.of(State.ACTIVE, State.DECOMMISSIONED),
        State.ACTIVE, Set.of(State.SUSPENDED, State.DECOMMISSIONED),
        State.SUSPENDED, Set.of(State.ACTIVE, State.DECOMMISSIONED),
        State.DECOMMISSIONED, Set.of(State.DECOMMISSIONED)
    );

    private final State value;

    private LifecycleState(final State value) {
        this.value = Objects.requireNonNull(value, "lifecycle state must not be null");
    }

    public static LifecycleState of(final State value) {
        return new LifecycleState(value);
    }

    public static LifecycleState provisioned() {
        return new LifecycleState(State.PROVISIONED);
    }

    public boolean canTransitionTo(final State target) {
        Objects.requireNonNull(target, "target state must not be null");
        if (value == target) {
            return true;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(value, Set.of()).contains(target);
    }

    public LifecycleState transitionTo(final State target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition from " + value + " to " + target);
        }
        if (value == target) {
            return this;
        }
        return new LifecycleState(target);
    }
}
