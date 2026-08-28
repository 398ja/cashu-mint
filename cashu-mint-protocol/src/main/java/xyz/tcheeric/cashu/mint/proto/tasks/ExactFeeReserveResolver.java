package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.common.nut02.KeySetResolver;
import xyz.tcheeric.cashu.common.nut02.UnknownKeySetException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Spec 002 FR-002: resolves {@code exactFeeReserve} for a melt request from
 * a single authoritative source (the gateway) and records the
 * caller-asserted NUT-15 input-fee assertion for forensics.
 *
 * <p>Per research R1, the mint-computed value is authoritative:
 * <ul>
 *   <li>lightning fee reserve: {@link Gateway#getFeeReserve(String)} —
 *       the gateway's own reported reserve for the specific quote; the
 *       gateway is the only party that knows its own routing economics.</li>
 *   <li>NUT-15 input fees: {@link PostMeltRequest#getFees(KeySetResolver)} — fees
 *       the proofs themselves charge for transit; these are mint revenue
 *       but still come out of the customer's funding budget, so they
 *       belong on the same side of the {@code sum(proofs) &gt;= invoice +
 *       exactFeeReserve} inequality.</li>
 * </ul>
 *
 * <p>The returned {@link Resolved} carries both components separately so
 * the saga record can persist them under {@code exact_fee_reserve} and
 * {@code asserted_fee_reserve} respectively (data-model § MeltSaga).
 */
public final class ExactFeeReserveResolver {

    private ExactFeeReserveResolver() {
    }

    /**
     * Resolves the fee reserve for a given quote. All amounts are {@code long}.
     *
     * @param gateway  the gateway adapter that originated the quote
     * @param quoteId  the melt quote id
     * @param request  the incoming melt request (carries the input proofs)
     * @param keySetResolver resolves each input's own keyset, so a melt spending
     *                 proofs from several keysets is priced per proof rather than
     *                 from whichever keyset happened to come first
     * @return resolved fee reserve bundle
     * @throws UnknownKeySetException when an input names a keyset this mint does not know
     */
    public static Resolved resolve(Gateway gateway, String quoteId,
                                   PostMeltRequest<?> request, KeySetResolver keySetResolver)
            throws UnknownKeySetException {
        long lightningReserve = gateway.getFeeReserve(quoteId);
        long inputFees = request.getFees(keySetResolver);
        long total = Math.addExact(lightningReserve, inputFees);
        return new Resolved(lightningReserve, inputFees, total);
    }

    @Getter
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Resolved {

        private long lightningReserve;
        private long inputFees;
        private long total;

        private Resolved(long lightningReserve, long inputFees, long total) {
            this.lightningReserve = lightningReserve;
            this.inputFees = inputFees;
            this.total = total;
        }
    }
}
