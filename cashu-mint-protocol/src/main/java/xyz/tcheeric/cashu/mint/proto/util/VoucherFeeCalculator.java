package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility for calculating voucher mint quote fees.
 *
 * <p>Implements the fee calculation formula: {@code fee = floor(amount * percentage / 100)}
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Uses floor rounding to avoid overcharging on fractional amounts</li>
 *   <li>Returns zero fee when amount or percentage is zero</li>
 *   <li>Validates inputs to prevent negative values</li>
 *   <li>Guards against integer overflow for very large amounts</li>
 * </ul>
 */
@Slf4j
public final class VoucherFeeCalculator {

    private VoucherFeeCalculator() {
    }

    /**
     * Calculate voucher minting fee using percentage-based pricing.
     *
     * <p>Formula: {@code fee = floor(voucherAmount * feePercentage / 100)}
     *
     * <p>Examples:
     * <ul>
     *   <li>1,000 sats @ 10% = 100 sats</li>
     *   <li>1,000 sats @ 1% = 10 sats</li>
     *   <li>1,005 sats @ 1% = 10 sats (floor of 10.05)</li>
     *   <li>1 sat @ 50% = 0 sats (floor of 0.5)</li>
     *   <li>Any amount @ 0% = 0 sats</li>
     * </ul>
     *
     * @param voucherAmount the face value of the voucher in satoshis
     * @param feePercentage the fee percentage (e.g., 10.0 for 10%)
     * @return the fee amount in satoshis (floored to avoid overcharging)
     * @throws IllegalArgumentException if inputs are invalid (negative)
     * @throws ArithmeticException      if calculation would overflow
     */
    public static long calculateFee(long voucherAmount, double feePercentage) {
        // Validate inputs
        if (voucherAmount < 0) {
            throw new IllegalArgumentException(
                String.format("Voucher amount cannot be negative: %d", voucherAmount));
        }
        if (feePercentage < 0) {
            throw new IllegalArgumentException(
                String.format("Fee percentage cannot be negative: %.2f", feePercentage));
        }

        // Handle zero cases (avoid unnecessary computation).
        //
        // NOT floored to the minimum: a zero face value or an explicitly
        // configured 0% fee are deliberate statements that nothing is owed,
        // and charging a minimum on them would invent a price the operator
        // did not ask for. The floor exists for the OPPOSITE case, where a
        // non-zero percentage of a small amount rounds away to nothing.
        if (voucherAmount == 0 || feePercentage == 0.0) {
            log.debug("Calculated voucher fee: amount={}, percentage={}%, fee=0 (zero input)",
                voucherAmount, feePercentage);
            return 0L;
        }

        // Calculate fee using floor to avoid overcharging
        double feeDouble = voucherAmount * feePercentage / 100.0;

        // Check for potential overflow before casting to long
        if (feeDouble > Long.MAX_VALUE) {
            throw new ArithmeticException(
                String.format("Voucher amount %d with fee percentage %.2f%% would cause overflow (result: %.0f > Long.MAX_VALUE)",
                    voucherAmount, feePercentage, feeDouble));
        }

        long fee = (long) Math.floor(feeDouble);

        log.debug("Calculated voucher fee: amount={}, percentage={}%, fee={}",
            voucherAmount, feePercentage, fee);

        return fee;
    }

    /**
     * Calculate the fee and raise it to {@code minimumFee} when rounding took
     * it to zero.
     *
     * <p>This is what callers that go on to CHARGE the fee should use.
     * {@link #calculateFee(long, double)} remains the pure percentage, and is
     * still the right call for anything that only reports or models a price.
     *
     * <p><strong>Why the floor is needed.</strong> The fee is
     * {@code floor(amount * percent / 100)}, so every amount below
     * {@code 100 / percent} rounds to zero: at 10% that is anything under 10
     * sats. Zero is not a cheap price, it is a zero-amount invoice, and the
     * payment chain accepts one at every step - the gateway creates it, it
     * settles trivially, and the mint then refuses its own webhook because a
     * non-positive amount can never match an authorised quote. Measured on
     * staging as 9 stranded quotes producing unbounded webhook rejections.
     *
     * <p>A zero face value or an explicit 0% fee still return zero: those say
     * nothing is owed, which is a decision rather than an accident.
     *
     * @param voucherAmount the face value of the voucher
     * @param feePercentage the fee percentage (e.g. 10.0 for 10%)
     * @param minimumFee    the smallest chargeable fee
     * @return the fee, never below {@code minimumFee} unless nothing is owed
     */
    public static long calculateChargeableFee(long voucherAmount, double feePercentage, long minimumFee) {
        long fee = calculateFee(voucherAmount, feePercentage);

        // Nothing owed: leave it alone. See the note above.
        if (voucherAmount == 0 || feePercentage == 0.0) {
            return 0L;
        }

        if (fee < minimumFee) {
            // A floor above the face value means the customer pays more than
            // the voucher is worth. That is always a misconfiguration: the
            // default of 1 can only reach it for a 1-sat voucher, but an
            // operator setting a larger minimum without adjusting the
            // denominations they sell would silently overcharge every small
            // one. Said at WARN rather than refused, because the mint is not
            // the right place to decide a business's pricing, and refusing
            // here would take out issuance rather than the bad setting.
            if (minimumFee > voucherAmount) {
                log.warn("voucher_fee minimum_exceeds_face_value amount={} minimum={} "
                                + "- the customer would pay more than the voucher is worth; "
                                + "check voucher.quote.fee-min-sat against the denominations sold",
                        voucherAmount, minimumFee);
            }
            log.info("voucher_fee floored amount={} percentage={}% computed={} charged={}",
                voucherAmount, feePercentage, fee, minimumFee);
            return minimumFee;
        }

        return fee;
    }
}
