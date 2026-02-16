package xyz.tcheeric.cashu.mint.admin.application.port.out;

/**
 * Publishes lifecycle domain events for mint aggregates.
 */
public interface MintLifecycleEventPublisher {

    /**
     * Publish the supplied lifecycle event.
     *
     * @param event the event to publish
     */
    void publish(MintLifecycleEvent event);
}
