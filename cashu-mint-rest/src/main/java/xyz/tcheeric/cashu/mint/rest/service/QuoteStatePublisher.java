package xyz.tcheeric.cashu.mint.rest.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.rest.event.QuoteStateChangeEvent;

/**
 * Listens for quote state change events and publishes to WebSocket subscribers.
 *
 * <p>Part of NUT-17 implementation for real-time quote state notifications.
 * Handles both mint quotes (NUT-04) and melt quotes (NUT-05).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cashu.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class QuoteStatePublisher {

    private final SubscriptionManager subscriptionManager;

    public QuoteStatePublisher(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * Handles quote state change events and publishes to WebSocket subscribers.
     *
     * <p>This method is async to avoid blocking the main request thread.
     */
    @Async
    @EventListener
    public void onQuoteStateChange(QuoteStateChangeEvent event) {
        try {
            subscriptionManager.publishQuoteState(
                    event.getKind(),
                    event.getQuoteId(),
                    event.getPayload()
            );
        } catch (Exception e) {
            log.error("quote_state_publish_error kind={} quote_id={} error={}",
                    event.getKind(),
                    event.getQuoteId(),
                    e.getMessage());
        }
    }
}
