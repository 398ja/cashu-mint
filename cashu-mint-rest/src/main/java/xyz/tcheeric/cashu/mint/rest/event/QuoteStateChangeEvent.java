package xyz.tcheeric.cashu.mint.rest.event;

import org.springframework.context.ApplicationEvent;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;

/**
 * Event fired when a quote's state changes.
 *
 * <p>Used for NUT-17 WebSocket notifications when mint or melt quotes
 * transition between states (UNPAID, PENDING, PAID, ISSUED).
 */
public class QuoteStateChangeEvent extends ApplicationEvent {

    private final SubscriptionKind kind;
    private final String quoteId;
    private final QuoteStatePayload payload;

    /**
     * Creates a new quote state change event.
     *
     * @param source the event source
     * @param kind the quote kind (bolt11_mint_quote or bolt11_melt_quote)
     * @param quoteId the quote ID
     * @param payload the quote state payload
     */
    public QuoteStateChangeEvent(Object source, SubscriptionKind kind, String quoteId, QuoteStatePayload payload) {
        super(source);
        this.kind = kind;
        this.quoteId = quoteId;
        this.payload = payload;
    }

    public SubscriptionKind getKind() {
        return kind;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public QuoteStatePayload getPayload() {
        return payload;
    }
}
