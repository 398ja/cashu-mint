package xyz.tcheeric.cashu.mint.rest.event;

import org.springframework.context.ApplicationEvent;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;

/**
 * Fired when a mint quote's state changes, carrying the quote as the bolt11 status route reports
 * it.
 *
 * <p>Separate from {@link QuoteStateChangeEvent} because a NUT-17 {@code bolt11_mint_quote}
 * notification carries the NUT-04 {@code MintQuoteResponse} itself, accounting fields included,
 * not a reduced state payload (cashu-mint#500).
 */
public class MintQuoteStateChangeEvent extends ApplicationEvent {

    private final PostMintQuoteResponse quote;

    /**
     * @param source the event source
     * @param quote  the quote as {@code GET /v1/mint/quote/bolt11/{id}} reports it
     */
    public MintQuoteStateChangeEvent(Object source, PostMintQuoteResponse quote) {
        super(source);
        this.quote = quote;
    }

    public PostMintQuoteResponse getQuote() {
        return quote;
    }
}
