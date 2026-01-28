package xyz.tcheeric.cashu.mint.rest.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.rest.event.ProofStateChangeEvent;
import xyz.tcheeric.cashu.mint.rest.event.QuoteStateChangeEvent;

import java.util.List;

/**
 * Service for publishing NUT-17 events.
 *
 * <p>This service should be called by controllers after proof or quote state
 * changes to trigger WebSocket notifications to subscribers.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cashu.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class Nut17EventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public Nut17EventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes proof spent events for a list of proofs.
     *
     * @param proofs the proofs that were spent (after swap/melt)
     */
    public <T extends Secret> void publishProofsSpent(List<Proof<T>> proofs) {
        for (Proof<T> proof : proofs) {
            publishProofState(proof.getSecret().toString(), NUT07.SPENT, null);
        }
    }

    /**
     * Publishes proof pending events for a list of proofs.
     *
     * @param proofs the proofs that are pending (during swap/melt)
     */
    public <T extends Secret> void publishProofsPending(List<Proof<T>> proofs) {
        for (Proof<T> proof : proofs) {
            publishProofState(proof.getSecret().toString(), NUT07.PENDING, null);
        }
    }

    /**
     * Publishes a proof state change event.
     *
     * @param y the proof's Y value (secret converted to point)
     * @param state the new state (UNSPENT, PENDING, SPENT)
     * @param witness optional witness data
     */
    public void publishProofState(String y, String state, String witness) {
        log.debug("nut17_event_publish proof_state y_prefix={} state={}",
                y != null && y.length() > 8 ? y.substring(0, 8) : y, state);
        eventPublisher.publishEvent(new ProofStateChangeEvent(this, y, state, witness));
    }

    /**
     * Publishes a mint quote state change.
     *
     * @param quoteId the quote ID
     * @param payload the quote state payload
     */
    public void publishMintQuoteState(String quoteId, QuoteStatePayload payload) {
        log.debug("nut17_event_publish mint_quote quote_id={} state={}", quoteId, payload.getState());
        eventPublisher.publishEvent(new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, quoteId, payload));
    }

    /**
     * Publishes a melt quote state change.
     *
     * @param quoteId the quote ID
     * @param payload the quote state payload
     */
    public void publishMeltQuoteState(String quoteId, QuoteStatePayload payload) {
        log.debug("nut17_event_publish melt_quote quote_id={} state={}", quoteId, payload.getState());
        eventPublisher.publishEvent(new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_melt_quote, quoteId, payload));
    }
}
