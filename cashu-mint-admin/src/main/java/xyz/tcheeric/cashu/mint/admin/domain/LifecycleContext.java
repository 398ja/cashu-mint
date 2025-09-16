package xyz.tcheeric.cashu.mint.admin.domain;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Context linking lifecycle events to configuration and notification policy state.
 */
@Value
@Accessors(fluent = true)
public class LifecycleContext {

    ConfigurationRevisionId configurationRevisionId;
    NotificationPolicySnapshot notificationPolicySnapshot;

    public LifecycleContext(final ConfigurationRevisionId configurationRevisionId,
                            final NotificationPolicySnapshot notificationPolicySnapshot) {
        this.configurationRevisionId = configurationRevisionId;
        this.notificationPolicySnapshot = notificationPolicySnapshot;
    }

    public static LifecycleContext empty() {
        return new LifecycleContext(null, null);
    }

    public boolean hasConfigurationRevision() {
        return configurationRevisionId != null;
    }

    public boolean hasNotificationPolicySnapshot() {
        return notificationPolicySnapshot != null;
    }
}
