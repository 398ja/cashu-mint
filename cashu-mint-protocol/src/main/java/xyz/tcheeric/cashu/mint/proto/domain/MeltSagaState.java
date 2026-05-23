package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * Spec 002 § Saga state machine. Every melt attempt walks through one of these
 * lifecycle states; transitions are append-only and recorded in the
 * {@code melt_saga_transition} ledger.
 */
public enum MeltSagaState {

    /** Proofs durably committed to {@code PENDING}; gateway.pay has not yet returned. */
    PROOFS_HELD,

    /** Gateway.pay returned a definitive {@code Success}; final invalidation pending. */
    PAYMENT_SENT,

    /** Terminal happy path: proofs SPENT, change (if any) issued. */
    COMPLETED,

    /** Terminal failure: gateway.pay returned {@code DefinitiveFailure}; proofs back to UNSPENT. */
    FAILED,

    /**
     * Terminal pathological state: external payment succeeded but the final
     * proof-invalidation commit failed. Proofs stay in {@code PENDING} until
     * operator action — no automatic retry (FR-007 / FR-011).
     */
    PAYMENT_SENT_BURN_FAILED,

    /**
     * Ambiguous provider response (timeout, 5xx, missing payment_hash). The
     * saga MUST NOT auto-advance to COMPLETED without independent
     * confirmation (FR-008). The reconciler polls {@code checkPaymentStatus}
     * for a bounded window, then escalates to operator alert.
     */
    PAYMENT_UNKNOWN;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == PAYMENT_SENT_BURN_FAILED;
    }
}
