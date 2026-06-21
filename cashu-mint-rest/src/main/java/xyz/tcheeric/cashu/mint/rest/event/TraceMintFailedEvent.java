package xyz.tcheeric.cashu.mint.rest.event;

import java.time.Instant;
import org.springframework.context.ApplicationEvent;

/**
 * Spec 036 — fired when a NUT-04 issuance attempt is rejected because the quote
 * is unpaid/invalid.
 *
 * <p>Consumed by {@code TraceMintProducer} to emit a {@code MINT_FAILED} trace
 * event. Per the verified invariant this carries <strong>no inputs and no
 * outputs</strong> — only the Lightning quote reference and an error code.
 */
public class TraceMintFailedEvent extends ApplicationEvent {

    private final String quoteId;
    private final long amount;
    private final String unit;
    private final String errorCode;
    private final String errorMessage;
    private final Instant transitionAt;

    public TraceMintFailedEvent(Object source, String quoteId, long amount, String unit,
                                String errorCode, String errorMessage, Instant transitionAt) {
        super(source);
        this.quoteId = quoteId;
        this.amount = amount;
        this.unit = unit;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.transitionAt = transitionAt;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public long getAmount() {
        return amount;
    }

    public String getUnit() {
        return unit;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getTransitionAt() {
        return transitionAt;
    }
}
