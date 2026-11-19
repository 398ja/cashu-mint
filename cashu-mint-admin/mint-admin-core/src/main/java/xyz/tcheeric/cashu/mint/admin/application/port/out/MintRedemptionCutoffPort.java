package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;

/**
 * Outbound port that delivers an approved redemption cutoff to the mint (cashu-mint#551).
 *
 * <p>The admin and the mint share only the vault, and the vault has no field for a cutoff
 * (ADR-0007), so this is a direct channel to the mint's {@code /admin} API. The mint re-checks
 * every rule (the keyset is archived, the date is ahead, a passed cutoff is final), because the
 * admin's view of the mint's keysets is a belief and the mint's is the fact.
 */
public interface MintRedemptionCutoffPort {

    /**
     * @throws Rejected      when the mint refused the cutoff; retrying will not change that
     * @throws RuntimeException when the mint could not be reached or failed; retrying may help
     */
    void apply(String keysetId, Instant redeemUntil, String reason, String setBy);

    /** The mint answered and said no. */
    class Rejected extends RuntimeException {
        public Rejected(final String message) {
            super(message);
        }
    }
}
