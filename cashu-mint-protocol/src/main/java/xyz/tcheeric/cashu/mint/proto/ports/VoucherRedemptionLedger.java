package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * The per-voucher redemption ledger (cashu-mint#540): how many of a coupon's sats have been
 * credited by a redeemer, against the sats issued for it, and which declared swap each part came
 * from.
 *
 * <p>Every claim is recorded under the NUT-19 request fingerprint of the swap that made it. That
 * key is what makes the ledger safe to fail:
 * <ul>
 *   <li>a retry of the same request (a lost reply, a process death between the claim and the
 *       signature) reuses its claim rather than adding a second one;</li>
 *   <li>the swap hold sweeper gives back the claim of a hold that never began signing;</li>
 *   <li>a redeemer whose credit is undone releases exactly that swap's claim, once.</li>
 * </ul>
 *
 * <p>Implementations must make {@link #claim} atomic per voucher id across mint instances, not
 * only within one JVM: the swap's proof lock is per instance, and two redeemers can present
 * different proofs of the same coupon to two instances at once.
 */
public interface VoucherRedemptionLedger {

    /**
     * Records a declared redemption, unless {@code refuseOverCap} is set and the coupon's total
     * would then exceed its cap, or its terms contradict the cap on record.
     *
     * @param request what to claim
     * @return what happened
     */
    Claim claim(ClaimRequest request);

    /**
     * Gives back the claim made under a request fingerprint whose swap signed nothing. Does nothing
     * when that request holds no standing claim, so it is safe to call whether or not the claim
     * reached the database.
     *
     * @param requestFingerprint the swap's NUT-19 request fingerprint
     * @return whether a claim was given back
     */
    boolean giveBack(String requestFingerprint);

    /**
     * Gives back the standing claim taken under a swap hold, for the hold sweeper releasing a hold
     * that never began signing.
     *
     * @param holdId the hold
     * @return how many claims were given back, 0 or 1
     */
    int giveBackForHold(String holdId);

    /**
     * A redeemer releases the claim of one of its own declared swaps, because the credit it stood
     * for was undone. Idempotent, and never more than that swap declared.
     *
     * @param requestFingerprint the receipt the declared swap answered with
     * @param voucherId          the voucher the redeemer believes it declared
     * @param redeemerDigest     the hex SHA-256 of the presenting redeemer's token
     * @param sats               the sats to release, or null for the whole declaration
     * @return what happened
     */
    Release release(String requestFingerprint, String voucherId, String redeemerDigest, Long sats);

    /**
     * A claim to record.
     *
     * @param voucherId          the coupon's voucher id
     * @param capSats            the sats issued for the coupon per its signed terms, or null when
     *                           they do not say. Recorded on first sight, and fills a cap recorded
     *                           as unknown.
     * @param sats               the sats this redemption credits, {@code > 0}
     * @param refuseOverCap      whether to refuse a claim over the cap or against a mismatched cap
     * @param requestFingerprint the swap's NUT-19 request fingerprint
     * @param redeemerDigest     hex SHA-256 of the declaring redeemer's token
     * @param holdId             the swap hold the claim is taken under
     */
    record ClaimRequest(String voucherId, Long capSats, long sats, boolean refuseOverCap,
                        String requestFingerprint, String redeemerDigest, String holdId) {
    }

    /** How a claim ended. */
    enum ClaimOutcome {
        /** Counted. */
        ACCEPTED,
        /** A retry of a request whose claim already stands: counted once, not again. */
        REUSED,
        /** Refused: the coupon's total would exceed its cap. */
        REFUSED_OVER_CAP,
        /** Refused: the coupon's terms derive a different cap from the one on record. */
        REFUSED_CAP_MISMATCH,
        /** Not counted: a retry of this request declared something else the first time. */
        DECLARATION_MISMATCH
    }

    /**
     * The outcome of a claim.
     *
     * @param outcome      what happened
     * @param redeemedSats the voucher's redeemed total after this claim, or as it stands when not
     *                     counted
     * @param capSats      the cap on record for the voucher, or {@code null} when unknown
     * @param capMismatch  whether the presented terms derived a different cap from the one on record
     */
    record Claim(ClaimOutcome outcome, long redeemedSats, Long capSats, boolean capMismatch) {

        /** @return whether this request now holds a standing claim */
        public boolean accepted() {
            return outcome == ClaimOutcome.ACCEPTED || outcome == ClaimOutcome.REUSED;
        }

        /** @return whether the redeemed total is now, or would have been, over the cap */
        public boolean overCap(long claimedSats) {
            if (capSats == null || outcome == ClaimOutcome.REUSED) {
                return false;
            }
            long after = accepted() ? redeemedSats : redeemedSats + claimedSats;
            return after > capSats;
        }
    }

    /** How a redeemer's release ended. */
    enum ReleaseOutcome {
        /** The claim was given back. */
        RELEASED,
        /** Already given back, by an earlier release or by the mint: nothing more to give. */
        ALREADY_RELEASED,
        /** No declared swap under that fingerprint. */
        NOT_FOUND,
        /** The swap declared another voucher. */
        OTHER_VOUCHER,
        /** Another redeemer declared that swap. */
        OTHER_REDEEMER,
        /** The release asks for other than the sats the swap declared. */
        NOT_THE_DECLARED_SATS
    }

    /**
     * The outcome of a release.
     *
     * @param outcome      what happened
     * @param releasedSats the sats given back by this call
     * @param declaredSats the sats the named swap declared, or 0 when there is none
     */
    record Release(ReleaseOutcome outcome, long releasedSats, long declaredSats) {
    }
}
