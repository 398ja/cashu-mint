package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * Voucher quote lifecycle state machine (spec 003 data-model § VoucherQuote).
 * Mirrors spec 001's {@code MintQuote.LifecycleState} shape so the CAS idiom
 * carries across; voucher-specific states are {@link #UNFUNDED} (no funding
 * row resolved yet) and {@link #FUNDED} (funding row attached but not yet
 * issued).
 *
 * <pre>
 *   UNFUNDED → FUNDED → ISSUING → ISSUED
 *       \_______↓________↓
 *               EXPIRED
 *   any         → FAILED
 * </pre>
 */
public enum VoucherLifecycleState {
    /** Quote created; awaiting funding resolution. */
    UNFUNDED,
    /** Funding row attached (CAS from UNFUNDED with funding_id set). */
    FUNDED,
    /** Mint task is signing blinded outputs; transient. */
    ISSUING,
    /** Signatures issued; terminal (NUT-19 idempotent replay). */
    ISSUED,
    /** TTL elapsed before reaching ISSUED. */
    EXPIRED,
    /** Operator-initiated triage state. */
    FAILED;

    /**
     * Whether no further transition is possible, so a row resting here can
     * never be stranded.
     *
     * <p>Stated in code rather than only in the javadoc above because #461's
     * reconciler-coverage rule has to compute over it: a machine-readable
     * predicate is what lets CI ask "is every non-terminal state swept?"
     * instead of a human re-reading prose.
     */
    public boolean isTerminal() {
        return this == ISSUED || this == EXPIRED || this == FAILED;
    }
}
