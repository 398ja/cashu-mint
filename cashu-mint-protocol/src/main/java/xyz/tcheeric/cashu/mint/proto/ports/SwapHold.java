package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;

import java.time.Instant;

/**
 * The durable record of a swap's exclusive hold on its input proofs.
 *
 * <p>A swap must sign its outputs before it can spend its inputs, and the two writes cannot share
 * a transaction because the vault is reached over its REST API. The hold covers that gap: the
 * inputs are claimed before signing and spent after, so no window exists in which the outputs are
 * redeemable while the inputs are still spendable.
 *
 * <p>What this record adds beyond the vault rows is the answer to the only question that matters
 * when a hold is found stranded: <em>had signing begun?</em> The two answers resolve in opposite
 * directions, and the vault rows alone cannot distinguish them, because a held proof looks
 * identical either way.
 *
 * @see SwapHoldPhase
 */
public interface SwapHold {

    /** The hold id carried on every input row this hold claimed. */
    String holdId();

    /** Which side of the signing step this hold had reached when it was last written. */
    SwapHoldPhase phase();

    /** How many inputs the hold claimed, so a partial commit is detectable. */
    int inputCount();

    /** When the hold was taken, which is what makes it possible to find one that is stale. */
    Instant createdAt();

    /** When the phase last advanced. */
    Instant updatedAt();
}
