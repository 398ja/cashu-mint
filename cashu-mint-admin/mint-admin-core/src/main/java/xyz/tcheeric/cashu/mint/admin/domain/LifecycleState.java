package xyz.tcheeric.cashu.mint.admin.domain;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        PROVISIONING,
        PROVISIONED,
        PROVISION_FAILED,
        ACTIVE,
        SUSPENDED,
        DECOMMISSIONED;

        Map<State, TransitionApproval> transitionApprovals() {
            return switch (this) {
                case PROVISIONING -> Map.of(
                    State.PROVISIONED, TransitionApproval.of(State.PROVISIONED, Set.of(),
                        "Automatic transition on successful vault provisioning."),
                    State.PROVISION_FAILED, TransitionApproval.of(State.PROVISION_FAILED, Set.of(),
                        "Automatic transition on permanent vault provisioning failure."),
                    State.DECOMMISSIONED, TransitionApproval.of(State.DECOMMISSIONED, Set.of("Operations"),
                        "Aborting a provisioning mint requires operations sign-off.")
                );
                case PROVISIONED -> Map.of(
                    State.ACTIVE, TransitionApproval.of(State.ACTIVE, Set.of("Operations", "Security"),
                        "Activation requires operations and security sign-off."),
                    State.DECOMMISSIONED, TransitionApproval.of(State.DECOMMISSIONED, Set.of("Operations"),
                        "Decommissioning a provisioned mint requires operations sign-off.")
                );
                case PROVISION_FAILED -> Map.of(
                    State.PROVISIONING, TransitionApproval.of(State.PROVISIONING, Set.of("Operations"),
                        "Retrying provisioning requires operations sign-off."),
                    State.DECOMMISSIONED, TransitionApproval.of(State.DECOMMISSIONED, Set.of("Operations"),
                        "Decommissioning a failed mint requires operations sign-off.")
                );
                case ACTIVE -> Map.of(
                    State.SUSPENDED, TransitionApproval.of(State.SUSPENDED, Set.of("Operations"),
                        "Suspending an active mint requires operations sign-off."),
                    State.DECOMMISSIONED, TransitionApproval.of(State.DECOMMISSIONED, Set.of("Operations", "Security"),
                        "Decommissioning an active mint requires operations and security sign-off.")
                );
                case SUSPENDED -> Map.of(
                    State.ACTIVE, TransitionApproval.of(State.ACTIVE, Set.of("Operations"),
                        "Reactivating a suspended mint requires operations sign-off."),
                    State.DECOMMISSIONED, TransitionApproval.of(State.DECOMMISSIONED, Set.of("Operations", "Security"),
                        "Decommissioning a suspended mint requires operations and security sign-off.")
                );
                case DECOMMISSIONED -> Map.of();
            };
        }

        Optional<TransitionApproval> findApprovalFor(final State target) {
            return Optional.ofNullable(transitionApprovals().get(target));
        }
    }

    public record TransitionApproval(State target, Set<String> requiredSignoffs, String description) {

        public TransitionApproval {
            requireNonNull(target, "target state must not be null");
            requireNonNull(requiredSignoffs, "required sign-offs must not be null");
            requireNonNull(description, "description must not be null");
            requiredSignoffs = Set.copyOf(requiredSignoffs);
        }

        public static TransitionApproval of(final State target,
                                            final Set<String> requiredSignoffs,
                                            final String description) {
            return new TransitionApproval(target, requiredSignoffs, description);
        }

        public static TransitionApproval noChange(final State target) {
            return new TransitionApproval(target, Set.of(), "State remains unchanged.");
        }

        public String summary() {
            final String signoffSummary = requiredSignoffs.isEmpty()
                ? "no approvals required"
                : "requires sign-off from: " + requiredSignoffs.stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            return target + " (" + signoffSummary + "; " + description + ")";
        }
    }

    private final State value;

    private LifecycleState(final State value) {
        this.value = requireNonNull(value, "lifecycle state must not be null");
    }

    public static LifecycleState of(final State value) {
        return new LifecycleState(requireNonNull(value, "lifecycle state must not be null"));
    }

    public static LifecycleState provisioned() {
        return new LifecycleState(State.PROVISIONED);
    }

    public boolean canTransitionTo(final State target) {
        return approvalRequirementsFor(target).isPresent();
    }

    public Optional<TransitionApproval> approvalRequirementsFor(final State target) {
        requireNonNull(target, "target state must not be null");
        if (value == target) {
            return Optional.of(TransitionApproval.noChange(target));
        }
        return value.findApprovalFor(target);
    }

    public TransitionApproval guardTransitionTo(final State target) {
        return approvalRequirementsFor(target)
            .orElseThrow(() -> new IllegalStateException(buildDisallowedTransitionMessage(target)));
    }

    public LifecycleState transitionTo(final State target) {
        guardTransitionTo(target);
        if (value == target) {
            return this;
        }
        return new LifecycleState(target);
    }

    public Map<State, TransitionApproval> allowedTransitions() {
        return value.transitionApprovals();
    }

    private String buildDisallowedTransitionMessage(final State target) {
        final String allowedDescriptions = value.transitionApprovals().values().stream()
            .map(TransitionApproval::summary)
            .collect(Collectors.joining(", "));
        if (allowedDescriptions.isEmpty()) {
            return "Cannot transition from " + value + " to " + target + ". State is terminal.";
        }
        return "Cannot transition from " + value + " to " + target + ". Allowed transitions: "
            + allowedDescriptions + ".";
    }
}
