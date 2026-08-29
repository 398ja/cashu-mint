package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * Which side of the signing step a swap hold had reached.
 *
 * <p>This is the whole reason the hold is recorded durably rather than inferred from the proof
 * rows: a stranded hold resolves in opposite directions depending on the answer, and a held proof
 * looks the same either way. Guessing releases inputs whose outputs are already in the wild, which
 * is the double-spend the hold exists to prevent.
 */
public enum SwapHoldPhase {

    /**
     * The inputs are claimed and nothing has been signed.
     *
     * <p>Safe to release: no output exists, so returning the inputs to {@code UNSPENT} restores
     * the state the wallet started from and costs it nothing.
     */
    HELD,

    /**
     * Signing has begun, so an output may exist and be redeemable through NUT-09 restore.
     *
     * <p>Must be committed, never released. This is written <em>before</em> the first signature,
     * not after, because a crash between the write and the signature must be read as "signing may
     * have begun". Recording it afterwards would leave the dangerous case indistinguishable from
     * {@link #HELD}, which is the ambiguity this enum exists to remove.
     */
    SIGNING,

    /** The inputs are spent and the swap is complete. Terminal. */
    COMMITTED,

    /** The inputs were returned unspent before anything was signed. Terminal. */
    RELEASED
}
