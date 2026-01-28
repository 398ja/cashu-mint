package xyz.tcheeric.cashu.mint.rest.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.rest.event.ProofStateChangeEvent;

/**
 * Listens for proof state change events and publishes to WebSocket subscribers.
 *
 * <p>Part of NUT-17 implementation for real-time proof state notifications.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cashu.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class ProofStatePublisher {

    private final SubscriptionManager subscriptionManager;

    public ProofStatePublisher(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * Handles proof state change events and publishes to WebSocket subscribers.
     *
     * <p>This method is async to avoid blocking the main request thread.
     */
    @Async
    @EventListener
    public void onProofStateChange(ProofStateChangeEvent event) {
        try {
            subscriptionManager.publishProofState(event.getY(), event.getState(), event.getWitness());
        } catch (Exception e) {
            log.error("proof_state_publish_error y_prefix={} state={} error={}",
                    event.getY() != null && event.getY().length() > 8 ? event.getY().substring(0, 8) : event.getY(),
                    event.getState(),
                    e.getMessage());
        }
    }
}
