package xyz.tcheeric.cashu.mint.admin.application.port.in;

/**
 * Executes operational workflows such as maintenance or key rotations.
 */
public interface ExecuteOperationalControlsUseCase {

    ExecuteOperationalControlsResponse handle(ExecuteOperationalControlsRequest request);

    enum OperationalCommand {
        SCHEDULE_MAINTENANCE,
        START_MAINTENANCE,
        COMPLETE_MAINTENANCE,
        ROTATE_KEYS,
        FORCE_CLOSE
    }

    record ExecuteOperationalControlsRequest(String mintId,
                                             String operatorId,
                                             OperationalCommand command,
                                             String versionTag) { }

    record ExecuteOperationalControlsResponse(String mintId,
                                              String controlId,
                                              String versionTag) { }
}
