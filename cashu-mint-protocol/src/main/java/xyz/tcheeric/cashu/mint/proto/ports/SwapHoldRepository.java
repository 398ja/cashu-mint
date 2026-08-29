package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for swap holds, so a hold outlives the request that took it.
 *
 * <p>Without this the mint cannot answer two questions after a crash: which holds are unresolved,
 * and whether each had begun signing. The vault knows a proof is held and by which hold id, but
 * nothing enumerates holds and nothing records the phase.
 *
 * <p>The default methods are no-ops so unit-test contexts without a database keep working, which
 * matches how {@code ProofVaultService} treats the same problem. A mint running without this
 * repository still cannot double-spend — the hold itself closes that — it simply resolves stranded
 * holds by hand.
 */
public interface SwapHoldRepository {

    /** Records a hold in {@link SwapHoldPhase#HELD}, before the inputs are claimed. */
    default void open(String holdId, int inputCount) {
    }

    /**
     * Advances a hold to a new phase.
     *
     * <p>Called with {@link SwapHoldPhase#SIGNING} <em>before</em> the first signature, so a crash
     * either side of the write is read as "signing may have begun".
     */
    default void advance(String holdId, SwapHoldPhase phase) {
    }

    default Optional<SwapHold> findById(String holdId) {
        return Optional.empty();
    }

    /**
     * Unresolved holds last touched before {@code olderThan}, oldest first.
     *
     * <p>The cutoff is what keeps a reconciler from racing a swap that is merely slow: an
     * in-flight hold is young, and a stranded one only becomes eligible once it has stopped
     * moving.
     */
    default List<SwapHold> findUnresolvedOlderThan(Instant olderThan) {
        return List.of();
    }
}
