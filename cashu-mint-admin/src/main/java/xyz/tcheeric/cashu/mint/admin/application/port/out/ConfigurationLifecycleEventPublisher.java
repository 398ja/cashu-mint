package xyz.tcheeric.cashu.mint.admin.application.port.out;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Publishes configuration lifecycle events to downstream consumers.
 */
public interface ConfigurationLifecycleEventPublisher {

    /**
     * Publish the supplied configuration lifecycle event.
     *
     * @param mintId the mint identifier
     * @param event the lifecycle event to publish
     */
    void publish(MintId mintId, ConfigurationLifecycleEvent event);
}
