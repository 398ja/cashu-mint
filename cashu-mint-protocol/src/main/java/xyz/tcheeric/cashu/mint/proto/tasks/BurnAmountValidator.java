package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;

/**
 * Spec 002 FR-001: enforces the NUT-05 melt-funding invariant
 * <pre>
 *   sum(proofs.amount) &gt;= invoiceAmount + exactFeeReserve
 * </pre>
 * using {@code long} arithmetic throughout (FR-009). The check is expressed
 * directly rather than through the legacy {@code totalAmount} formulation
 * which was opaque about whether input fees were on the same side of the
 * inequality as the lightning fee reserve.
 *
 * <p>Throws a typed {@link CashuErrorException} with the
 * {@code insufficient_input} error code on rejection. Callers MUST NOT
 * proceed to any external payment (FR-007 / SC-001).
 */
public final class BurnAmountValidator {

    private BurnAmountValidator() {
    }

    /**
     * Verifies the proof sum strictly covers the invoice plus the resolved
     * fee reserve. Uses {@link Math#addExact} so a pathological
     * {@code Long.MAX_VALUE} edge case raises an arithmetic error rather
     * than wrapping around to a false acceptance.
     *
     * @param proofSum         sum of input proof amounts
     * @param invoiceAmount    the lightning invoice amount being settled
     * @param exactFeeReserve  the resolved fee reserve (gateway-reported
     *                         lightning reserve plus any NUT-15 input fees)
     * @throws CashuErrorException carrying {@code insufficient_input} when
     *         {@code proofSum &lt; invoiceAmount + exactFeeReserve}
     */
    public static void requireFunded(long proofSum, long invoiceAmount, long exactFeeReserve)
            throws CashuErrorException {
        if (invoiceAmount <= 0 || exactFeeReserve < 0 || proofSum < 0) {
            throw new CashuErrorException(new ErrorResponse(
                    "insufficient_input",
                    "Amount fields must be non-negative; invoice must be positive").toJson());
        }
        long required;
        try {
            required = Math.addExact(invoiceAmount, exactFeeReserve);
        } catch (ArithmeticException overflow) {
            throw new CashuErrorException(new ErrorResponse(
                    "insufficient_input",
                    "Invoice + fee reserve overflows long").toJson());
        }
        if (proofSum < required) {
            throw new CashuErrorException(new ErrorResponse(
                    "insufficient_input",
                    String.format("sum(proofs)=%d < invoice=%d + feeReserve=%d (need %d)",
                            proofSum, invoiceAmount, exactFeeReserve, required)).toJson());
        }
    }
}
