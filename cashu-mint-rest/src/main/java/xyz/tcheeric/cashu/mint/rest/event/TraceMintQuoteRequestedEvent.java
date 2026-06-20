package xyz.tcheeric.cashu.mint.rest.event;

import java.time.Instant;
import org.springframework.context.ApplicationEvent;

/**
 * Spec 036 — fired after a NUT-04 mint quote is successfully created.
 *
 * <p>Consumed by {@code TraceMintProducer} to emit a {@code MINT_QUOTE_REQUESTED}
 * trace event to the cashu-ledger forensic ledger. Carries only the Lightning
 * quote context the mint legitimately knows; no proof/token data.
 */
public class TraceMintQuoteRequestedEvent extends ApplicationEvent {

    private final String quoteId;
    private final String request;
    private final String paymentHash;
    private final long amount;
    private final String unit;
    private final int expiry;
    private final Instant transitionAt;

    public TraceMintQuoteRequestedEvent(Object source, String quoteId, String request,
                                        String paymentHash, long amount, String unit,
                                        int expiry, Instant transitionAt) {
        super(source);
        this.quoteId = quoteId;
        this.request = request;
        this.paymentHash = paymentHash;
        this.amount = amount;
        this.unit = unit;
        this.expiry = expiry;
        this.transitionAt = transitionAt;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public String getRequest() {
        return request;
    }

    public String getPaymentHash() {
        return paymentHash;
    }

    public long getAmount() {
        return amount;
    }

    public String getUnit() {
        return unit;
    }

    public int getExpiry() {
        return expiry;
    }

    public Instant getTransitionAt() {
        return transitionAt;
    }
}
