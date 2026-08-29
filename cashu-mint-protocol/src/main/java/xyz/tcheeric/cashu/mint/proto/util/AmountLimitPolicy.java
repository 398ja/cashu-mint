package xyz.tcheeric.cashu.mint.proto.util;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties.PaymentMethodLimits;

import java.util.List;
import java.util.Optional;

/**
 * The single object that both advertises the NUT-04 / NUT-05 amount limits and
 * enforces them.
 *
 * <p>Issue #390: the limits used to be numbers in configuration that only
 * {@code /v1/info} ever read, so {@code max_amount} was a promise to wallets
 * with nothing behind it. Rather than adding a second place that must be kept
 * in step, the quote paths and the info endpoint now consult this one policy:
 * {@link #mintMethods()} is what gets advertised and
 * {@link #requireWithinMintLimits} is what gets enforced, over the same
 * {@link PaymentMethodLimits} instances. The advertised number is the enforced
 * number by construction.
 *
 * <p>Limits are matched on the <em>unit</em>, because that is what the amount is
 * denominated in: a mint that advertises it will issue at most 10 000 sat means
 * that whichever rail carries the payment. A unit the mint advertises no limits
 * for at all is refused rather than waved through, since an unmatched request
 * would otherwise reopen exactly the unenforced-limit hole this class closes.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/04.md">NUT-04</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/05.md">NUT-05</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
public final class AmountLimitPolicy {

    private final MintCapabilityProperties capabilities;

    public AmountLimitPolicy(@NonNull MintCapabilityProperties capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Returns the mint-side limits, exactly as advertised under NUT-04.
     *
     * @return the configured mint payment methods
     */
    public List<PaymentMethodLimits> mintMethods() {
        return capabilities.getMintMethods();
    }

    /**
     * Returns the melt-side limits, exactly as advertised under NUT-05.
     *
     * @return the configured melt payment methods
     */
    public List<PaymentMethodLimits> meltMethods() {
        return capabilities.getMeltMethods();
    }

    /**
     * Rejects a mint-quote amount the mint advertises it will not issue.
     *
     * @param amount the requested amount
     * @param unit   the unit requested
     * @throws CashuErrorException when the amount falls outside the advertised range
     */
    public void requireWithinMintLimits(long amount, @NonNull String unit)
            throws CashuErrorException {
        requireWithinLimits(mintMethods(), amount, unit, "mint");
    }

    /**
     * Rejects a melt-quote amount the mint advertises it will not pay.
     *
     * @param amount the invoice amount the gateway resolved
     * @param unit   the unit requested
     * @throws CashuErrorException when the amount falls outside the advertised range
     */
    public void requireWithinMeltLimits(long amount, @NonNull String unit)
            throws CashuErrorException {
        requireWithinLimits(meltMethods(), amount, unit, "melt");
    }

    private void requireWithinLimits(List<PaymentMethodLimits> advertised,
                                     long amount,
                                     String unit,
                                     String operation) throws CashuErrorException {
        PaymentMethodLimits limits = findAdvertised(advertised, unit)
                .orElseThrow(() -> new CashuErrorException(CashuErrorCode.unit_not_supported,
                        "This mint advertises no %s limits for %s".formatted(operation, unit)));

        if (amount < limits.getMinAmount() || amount > limits.getMaxAmount()) {
            throw new CashuErrorException(CashuErrorCode.amount_outside_limit_range,
                    "%s amount %d %s is outside the advertised range %d-%d".formatted(
                            operation, amount, unit, limits.getMinAmount(), limits.getMaxAmount()));
        }
    }

    private Optional<PaymentMethodLimits> findAdvertised(List<PaymentMethodLimits> advertised,
                                                         String unit) {
        return advertised.stream()
                .filter(limits -> unit.equalsIgnoreCase(limits.getUnit()))
                .findFirst();
    }

}
