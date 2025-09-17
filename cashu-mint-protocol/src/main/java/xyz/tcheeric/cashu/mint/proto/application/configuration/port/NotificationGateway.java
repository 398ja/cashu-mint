package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.NotificationPayload;

/**
 * Dispatches configuration governance notifications.
 */
public interface NotificationGateway {

    void notify(NotificationPayload payload);
}
