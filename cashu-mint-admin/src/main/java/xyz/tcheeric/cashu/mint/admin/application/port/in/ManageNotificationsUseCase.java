package xyz.tcheeric.cashu.mint.admin.application.port.in;

/**
 * Coordinates notification policy changes and delivery channel management.
 */
public interface ManageNotificationsUseCase {

    ManageNotificationsResponse handle(ManageNotificationsRequest request);

    enum NotificationCommand {
        CREATE_POLICY,
        UPDATE_POLICY,
        ENABLE_CHANNEL,
        DISABLE_CHANNEL,
        ACKNOWLEDGE
    }

    record ManageNotificationsRequest(String mintId,
                                      String policyId,
                                      NotificationCommand command,
                                      String versionTag) { }

    record ManageNotificationsResponse(String policyId,
                                       String versionTag) { }
}
