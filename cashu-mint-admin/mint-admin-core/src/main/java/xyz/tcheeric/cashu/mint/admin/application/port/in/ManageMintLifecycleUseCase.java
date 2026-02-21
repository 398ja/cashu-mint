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
                                      String versionTag,
                                      String requestId,
                                      String correlationId,
                                      java.util.Map<String, String> configurationParameters) {

        public ManageMintLifecycleRequest(String mintId, String operatorId, LifecycleCommand command,
                                          String versionTag, String requestId, String correlationId) {
            this(mintId, operatorId, command, versionTag, requestId, correlationId, java.util.Map.of());
        }
    }

    record ManageMintLifecycleResponse(String mintId,
                                       LifecycleState.State lifecycleState,
                                       String versionTag) { }
}
