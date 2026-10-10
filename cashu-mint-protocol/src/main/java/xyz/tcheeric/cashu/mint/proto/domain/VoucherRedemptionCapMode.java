package xyz.tcheeric.cashu.mint.proto.domain;

import java.util.Locale;

/**
 * What the mint does with a declared voucher redemption that would credit more of a coupon than
 * was ever issued for it (cashu-mint#540).
 *
 * <ul>
 *   <li>{@code OFF}: declarations are ignored. The mint behaves as it did before #540.</li>
 *   <li>{@code LOG} (default): every declaration is recorded, and one that goes over the coupon's
 *       cap is accepted but logged as {@code voucher_overspend} and counted.</li>
 *   <li>{@code ENFORCE}: a declaration that would go over the cap refuses the swap before
 *       anything is signed.</li>
 * </ul>
 */
public enum VoucherRedemptionCapMode {

    OFF,

    LOG,

    ENFORCE;

    /**
     * Label value for metrics and logs: the lower-case name, matching the property value.
     *
     * @return the label
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
