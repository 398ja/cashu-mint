package xyz.tcheeric.cashu.mint.proto.voucher;

/**
 * The voucher redemption cap the running mint configured, for the swap task to find
 * (cashu-mint#540). Kept out of {@code MintIntegrityContext} for the reason
 * {@link InstalledVoucherIssuerBinding} gives: that context is cleared on the JPA installer's
 * shutdown, which would silently relax a mint configured to {@code enforce}.
 */
public final class InstalledVoucherRedemptionCap {

    private static volatile VoucherRedemptionCap installed;

    private InstalledVoucherRedemptionCap() {
    }

    /**
     * Installs the configured cap, replacing any previous one.
     *
     * @param cap the cap to apply; null restores the unconfigured default
     */
    public static void install(VoucherRedemptionCap cap) {
        installed = cap;
    }

    /** @return the installed cap, or {@link VoucherRedemptionCap#unconfigured()}. Never null. */
    public static VoucherRedemptionCap current() {
        VoucherRedemptionCap cap = installed;
        return cap != null ? cap : VoucherRedemptionCap.unconfigured();
    }
}
