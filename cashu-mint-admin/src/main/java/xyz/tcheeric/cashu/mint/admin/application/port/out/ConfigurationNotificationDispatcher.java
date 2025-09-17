package xyz.tcheeric.cashu.mint.admin.application.port.out;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Dispatches notifications related to configuration lifecycle transitions.
 */
public interface ConfigurationNotificationDispatcher {

    /**
     * Dispatch notifications for the supplied lifecycle event.
     *
     * @param mintId the mint identifier
     * @param event the lifecycle event
     * @param configurationSet the configuration revision associated with the event
     */
    void dispatch(MintId mintId, ConfigurationLifecycleEvent event, ConfigurationSet configurationSet);
}
