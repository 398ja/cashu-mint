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

        // Handle zero cases (avoid unnecessary computation)
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
}
