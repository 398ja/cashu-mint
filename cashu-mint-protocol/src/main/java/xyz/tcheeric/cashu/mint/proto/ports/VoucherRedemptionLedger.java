package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * The per-voucher redemption ledger (cashu-mint#540): how many of a coupon's sats have been
 * credited by a redeemer, against the sats issued for it.
 *
 * <p>Implementations must make {@link #claim} atomic per voucher id across mint instances, not
 * only within one JVM: the swap's proof lock is per instance, and two redeemers can present
 * different proofs of the same coupon to two instances at once. A single conditional
 * {@code UPDATE} on one row per voucher id does that.
 */
public interface VoucherRedemptionLedger {

    /**
     * Adds {@code sats} to the voucher's redeemed total, unless {@code refuseOverCap} is set and
     * the total would then exceed the cap.
     *
     * @param voucherId     the coupon's voucher id
     * @param capSats       the sats issued for the coupon, or {@code null} when unknown. Recorded
     *                      on first sight. An unknown cap never refuses.
     * @param sats          the sats this redemption credits, {@code > 0}
     * @param refuseOverCap whether to refuse a claim that would exceed the cap
     * @return what happened
     */
    Claim claim(String voucherId, Long capSats, long sats, boolean refuseOverCap);

    /**
     * Gives back a claim whose swap was refused before anything was signed. Never takes the
     * total below zero.
     *
     * @param voucherId the coupon's voucher id
     * @param sats      the sats the refused claim added
     */
    void release(String voucherId, long sats);

    /**
     * The outcome of a claim.
     *
     * @param accepted     whether the claim was recorded
     * @param redeemedSats the voucher's redeemed total after this claim, or before it when refused
     * @param capSats      the cap on record for the voucher, or {@code null} when unknown
     */
    record Claim(boolean accepted, long redeemedSats, Long capSats) {

        /** @return whether the redeemed total is now, or would have been, over the cap */
        public boolean overCap(long claimedSats) {
            if (capSats == null) {
                return false;
            }
            long after = accepted ? redeemedSats : redeemedSats + claimedSats;
            return after > capSats;
        }
    }
}
