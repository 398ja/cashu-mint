package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationGovernanceEvent;

/**
 * Publishes configuration governance events to interested listeners.
 */
public interface DomainEventPublisher {

    void publish(ConfigurationGovernanceEvent event);
}
