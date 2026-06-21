package xyz.tcheeric.cashu.mint.rest.event;

import java.time.Instant;
import org.springframework.context.ApplicationEvent;

/**
 * Spec 036 — fired after a NUT-05 melt quote is successfully created.
 *
 * <p>Consumed by {@code TraceMintProducer} to emit a {@code MELT_QUOTE_REQUESTED}
 * trace event. Carries the Lightning quote context plus the fee reserve; no
 * proof/token data.
 */
public class TraceMeltQuoteRequestedEvent extends ApplicationEvent {

    private final String quoteId;
    private final String request;
    private final long amount;
    private final long feeReserve;
    private final String unit;
    private final int expiry;
    private final Instant transitionAt;

    public TraceMeltQuoteRequestedEvent(Object source, String quoteId, String request,
                                        long amount, long feeReserve, String unit,
                                        int expiry, Instant transitionAt) {
        super(source);
        this.quoteId = quoteId;
        this.request = request;
        this.amount = amount;
        this.feeReserve = feeReserve;
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

    public long getAmount() {
        return amount;
    }

    public long getFeeReserve() {
        return feeReserve;
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
