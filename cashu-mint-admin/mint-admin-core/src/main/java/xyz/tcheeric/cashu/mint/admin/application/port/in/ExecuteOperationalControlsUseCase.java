package xyz.tcheeric.cashu.mint.admin.application.port.in;

import java.time.Instant;

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
                                             String versionTag,
                                             String reason,
                                             Integer durationMinutes) {

        public ExecuteOperationalControlsRequest(String mintId, String operatorId,
                                                 OperationalCommand command, String versionTag) {
            this(mintId, operatorId, command, versionTag, null, null);
        }
    }

    record ExecuteOperationalControlsResponse(String mintId,
                                              String controlId,
                                              String status,
                                              Instant scheduledAt,
                                              String versionTag,
                                              String message) {

        public ExecuteOperationalControlsResponse(String mintId, String controlId, String versionTag) {
            this(mintId, controlId, null, null, versionTag, null);
        }
    }
}
