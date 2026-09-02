package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

/**
 * Spending condition for a {@code P2PK_VOUCHER} proof: a voucher that is also P2PK-locked.
 *
 * <p>Runs <strong>both</strong> conditions. The voucher half checks expiry, the issuer
 * signature, double-spend state, and BDHKE; the P2PK half requires a witness signature from
 * the key in {@code data}. A proof of this kind is spendable only if both pass.
 *
 * <h2>Why this class exists rather than a branch</h2>
 *
 * <p>Running only one of the two is the failure mode the {@code P2PK_VOUCHER} kind was created
 * to prevent, and either half alone looks like it works:
 *
 * <ul>
 *   <li><strong>Voucher checks only</strong> — the lock becomes advisory. The mint verifies
 *       who issued the voucher and never that the spender holds the key it was locked to, so
 *       a thief who obtains the proof can spend it. This is what a bare {@code VOUCHER} secret
 *       carrying P2PK tags would have done.</li>
 *   <li><strong>P2PK checks only</strong> — the lock is enforced, but nothing verifies the
 *       issuer signature or the expiry, so a forged or expired voucher locked to the
 *       spender's own key would be honoured.</li>
 * </ul>
 *
 * <p>Both halves are delegated rather than reimplemented, so this kind cannot drift from the
 * rules applied to an ordinary voucher or an ordinary P2PK proof.
 *
 * <h2>Dispatch ordering</h2>
 *
 * <p>{@code P2PKVoucherSecret} extends {@code P2PKSecret}, so an {@code instanceof P2PKSecret}
 * branch matches it. Any dispatcher must therefore test for this kind <em>before</em> both the
 * P2PK branch and any voucher branch — otherwise the proof falls into a condition that runs
 * half the checks, silently.
 *
 * <h2>Not advertised in {@code /v1/info}</h2>
 *
 * <p>Deliberately absent from {@code NutSupport}. That enum requires a claim to be backed by a
 * wiring witness and, where the spec publishes them, by passing vectors — it exists because
 * NUT-11 was once advertised on the strength of a class existing while every published vector
 * failed. {@code P2PK_VOUCHER} is an Imani extension with no NUT number and no published
 * vectors, so there is nothing to key an honest claim on, and the existing {@code VOUCHER}
 * kind is unadvertised for the same reason.
 *
 * <p>The practical consequence is that a wallet cannot discover this kind from
 * {@code /v1/info}. That is the correct outcome while it is a private extension: a wallet that
 * does not know the kind will reject the secret at parse rather than treat it as
 * anyone-can-spend, because {@code Kind.valueOf} throws on an unknown name. Advertising it
 * becomes worth doing if the kind is ever standardised and given a number.
 *
 * @see VoucherSpendingCondition
 * @see P2PKSpendingCondition
 */
@Slf4j
public class P2PKVoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    private final VoucherSpendingCondition<T> voucherCondition;
    private final P2PKSpendingCondition p2pkCondition;

    /**
     * @param mint                the mint whose keyset signs the proof
     * @param mintProtocolService resolves the private key for BDHKE verification
     * @param transaction         the surrounding swap or melt; required because NUT-11
     *                            {@code SIG_ALL} signs over the whole transaction rather than
     *                            one proof
     */
    public P2PKVoucherSpendingCondition(@NonNull Mint mint,
                                        @NonNull MintProtocolService mintProtocolService,
                                        @NonNull P2PKTransaction transaction) {
        this.voucherCondition = new VoucherSpendingCondition<>(mint, mintProtocolService);
        this.p2pkCondition = new P2PKSpendingCondition(transaction);
    }

    /**
     * Verifies the voucher conditions and then the lock.
     *
     * <p>Voucher first, deliberately: an expired or unsigned voucher is refused before the
     * signature work, and its error names the real reason rather than a missing witness.
     *
     * <p>Neither check is conditional on the other's outcome. Both throw, so reaching the end
     * of this method means both passed.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void verify(@NonNull Proof<T> proof) throws CashuErrorException {
        log.debug("Verifying P2PK_VOUCHER proof: amount={}", proof.getAmount());

        voucherCondition.verify(proof);
        p2pkCondition.verify((Proof<P2PKSecret>) proof);

        log.info("p2pk_voucher_proof_verified amount={}", proof.getAmount());
    }
}
