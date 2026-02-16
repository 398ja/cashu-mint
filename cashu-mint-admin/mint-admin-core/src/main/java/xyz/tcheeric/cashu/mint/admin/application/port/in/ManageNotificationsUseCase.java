package xyz.tcheeric.cashu.mint.admin.application.port.in;

import java.util.List;
import java.util.Map;

/**
 * Coordinates notification policy changes, delivery channel management, and alert workflows.
 */
public interface ManageNotificationsUseCase {

    ManageNotificationsResponse handle(ManageNotificationsRequest request);

    enum NotificationCommand {
        CREATE_POLICY,
        UPDATE_POLICY,
        ENABLE_CHANNEL,
        DISABLE_CHANNEL,
        ACKNOWLEDGE,
        CREATE_ALERT,
        SILENCE_ALERT,
        UNSILENCE_ALERT,
        ESCALATE_ALERT
    }

    record ManageNotificationsRequest(String mintId,
                                      String policyId,
                                      NotificationCommand command,
                                      String versionTag,
                                      String alertId,
                                      String severity,
                                      String summary,
                                      Map<String, Object> labels,
                                      Integer silenceMinutes,
                                      String reason) {

        public ManageNotificationsRequest(String mintId,
                                          String policyId,
                                          NotificationCommand command,
                                          String versionTag) {
            this(mintId, policyId, command, versionTag, null, null, null, Map.of(), null, null);
        }
    }

    record ManageNotificationsResponse(String policyId,
                                       String versionTag,
                                       String alertId,
                                       String mintId,
                                       String severity,
                                       String summary,
                                       boolean acknowledged,
                                       boolean silenced,
                                       Integer silenceMinutes,
                                       List<String> escalations,
                                       String message) { }
}
