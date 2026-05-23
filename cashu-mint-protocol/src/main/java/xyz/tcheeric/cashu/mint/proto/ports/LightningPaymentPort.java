package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;

import java.time.Duration;

/**
 * Spec 002 R4 — wrapper port over {@code payment-adapter}'s
 * {@code Gateway.pay(quoteId)} that surfaces a typed {@link PaymentOutcome}
 * instead of the legacy boolean/throw signature. Implementations are
 * responsible for strict response parsing — ambiguous, partial, or 5xx
 * responses MUST classify as {@link PaymentOutcome.Unknown}, never silently
 * accepted as success (Constitution VI / FR-008).
 *
 * <p>The mint side never invokes {@code Gateway.pay} directly under spec 002;
 * it always goes through this port so the parsing logic lives in exactly one
 * place and is testable via failure injection (see
 * {@code MockLightningPaymentPort} in the IT module).
 */
public interface LightningPaymentPort {

    /**
     * Submits the previously-prepared melt quote to the lightning provider
     * and waits up to {@code timeout} for a response.
     *
     * @param quoteId the melt quote id (already known to the gateway)
     * @param timeout maximum time to wait for a definitive response
     * @return one of {@link PaymentOutcome.Success},
     *         {@link PaymentOutcome.DefinitiveFailure}, or
     *         {@link PaymentOutcome.Unknown}
     */
    PaymentOutcome pay(String quoteId, Duration timeout);

    /**
     * Idempotent read of the provider's current status for the quote. Used by
     * the {@code MeltSagaReconciler} to resolve {@code PAYMENT_UNKNOWN} sagas
     * without retrying the original payment call (FR-007).
     *
     * @param quoteId the melt quote id
     * @return one of {@link PaymentOutcome.Success},
     *         {@link PaymentOutcome.DefinitiveFailure}, or
     *         {@link PaymentOutcome.Unknown}
     */
    PaymentOutcome checkStatus(String quoteId);
}
