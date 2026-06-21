package xyz.tcheeric.cashu.mint.rest.event;

import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEvent;

/**
 * Spec 036 — fired when a NUT-05 melt fails its Lightning payment and the mint
 * releases (refunds) the input proofs.
 *
 * <p>Consumed by {@code TraceMintProducer} to emit a {@code MELT_FAILED} trace
 * event carrying the released <strong>input</strong> proofs (public {@code Y}
 * only — no secrets) and an error code; no outputs.
 */
public class TraceMeltFailedEvent extends ApplicationEvent {

    private final String quoteId;
    private final long amount;
    private final String unit;
    private final List<TraceProofInput> inputs;
    private final String errorCode;
    private final String errorMessage;
    private final Instant transitionAt;

    public TraceMeltFailedEvent(Object source, String quoteId, long amount, String unit,
                                List<TraceProofInput> inputs, String errorCode,
                                String errorMessage, Instant transitionAt) {
        super(source);
        this.quoteId = quoteId;
        this.amount = amount;
        this.unit = unit;
        this.inputs = inputs;
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

    public List<TraceProofInput> getInputs() {
        return inputs;
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
