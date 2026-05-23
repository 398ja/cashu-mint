package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * Spec 002 research R4 — typed outcome from
 * {@link xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort#pay} so the
 * caller can react to the three concrete cases without parsing provider
 * responses inline.
 *
 * <p>The third case ({@link Unknown}) is intentionally not collapsed into
 * either of the other two. Ambiguity is a first-class outcome that flips the
 * saga into {@code PAYMENT_UNKNOWN}; the reconciler resolves it with
 * follow-on read-only provider calls.
 */
public sealed interface PaymentOutcome
        permits PaymentOutcome.Success,
                PaymentOutcome.DefinitiveFailure,
                PaymentOutcome.Unknown {

    /** Provider acknowledged the payment with all expected fields. */
    record Success(String paymentHash,
                   long amountSettled,
                   long feePaid,
                   String providerEventId) implements PaymentOutcome {}

    /** Provider returned a definitive failure (4xx, known error code). */
    record DefinitiveFailure(String reason, String providerCode) implements PaymentOutcome {}

    /**
     * Provider response was ambiguous: timeout, 5xx, missing payment_hash,
     * missing status. The saga MUST stay in {@code PAYMENT_UNKNOWN} until
     * an independent read confirms one of the other two outcomes (FR-008).
     */
    record Unknown(String reason) implements PaymentOutcome {}
}
