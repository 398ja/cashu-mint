package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;
import java.util.Objects;

/**
 * The instant from which a retired keyset stops redeeming (cashu-mint#551).
 *
 * <p>An archived keyset refuses to sign but keeps verifying and redeeming its proofs, which is
 * what NUT-02 {@code active: false} promises a holder (ADR-0004). A cutoff ends that promise on a
 * date the operator announced: from {@link #redeemUntil()} on, the mint refuses the keyset's
 * proofs as inputs and stops advertising it. It exists for keysets whose private keys can no
 * longer be trusted, where a forged proof is indistinguishable from an honest one and a dated end
 * to redemption is the only mitigation.
 *
 * @param keysetId    the keyset id wallets name
 * @param redeemUntil the first instant at which inputs on the keyset are refused
 * @param reason      the operator's reason, for the record
 * @param setBy       who set it, as the caller reported it
 * @param setAt       when the mint recorded it
 */
public record KeysetRedemptionCutoff(String keysetId, Instant redeemUntil, String reason,
                                     String setBy, Instant setAt) {

    public KeysetRedemptionCutoff {
        Objects.requireNonNull(keysetId, "keyset id must not be null");
        Objects.requireNonNull(redeemUntil, "redeem-until must not be null");
    }

    /**
     * @param now the instant being judged
     * @return true when proofs on this keyset may no longer be redeemed at {@code now}
     */
    public boolean hasPassed(Instant now) {
        return !now.isBefore(redeemUntil);
    }
}
