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
    FAILED
}
