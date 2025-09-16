package xyz.tcheeric.cashu.mint.admin.cli.model;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;

/**
 * Machine-readable response summarising the outcome of a lifecycle action.
 */
public record MintLifecycleResponse(MintLifecycleOperation operation,
                                    String mintId,
                                    LifecycleState.State previousState,
                                    LifecycleState.State currentState,
                                    String versionTag,
                                    boolean changed,
                                    String message) {

    public MintLifecycleResponse {
        operation = Objects.requireNonNull(operation, "operation");
        mintId = ModelValidations.requireMintId(mintId);
        versionTag = ModelValidations.requireText(versionTag, "versionTag");
        message = message == null ? "" : message;
    }

    public boolean idempotent() {
        return !changed;
    }
}
