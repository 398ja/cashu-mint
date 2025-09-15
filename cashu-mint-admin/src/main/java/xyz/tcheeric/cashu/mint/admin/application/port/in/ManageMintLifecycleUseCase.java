package xyz.tcheeric.cashu.mint.admin.application.port.in;

import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;

/**
 * Governs lifecycle transitions for mint aggregates.
 */
public interface ManageMintLifecycleUseCase {

    ManageMintLifecycleResponse handle(ManageMintLifecycleRequest request);

    enum LifecycleCommand {
        CREATE,
        UPDATE_CONFIGURATION,
        PAUSE,
        RESUME,
        RETIRE
    }

    record ManageMintLifecycleRequest(String mintId,
                                      String operatorId,
                                      LifecycleCommand command,
                                      String versionTag) { }

    record ManageMintLifecycleResponse(String mintId,
                                       LifecycleState.State lifecycleState,
                                       String versionTag) { }
}
