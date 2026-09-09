package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKTransaction;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.ProofAuthenticity;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKVoucherSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;

import java.util.List;

/**
 * Utility class for detecting voucher secrets without creating a hard dependency.
 *
 * <p>This class uses reflection-based detection to identify VoucherSecret instances
 * even when cashu-voucher-domain is marked as an optional dependency. This allows
 * the mint protocol to reject vouchers (Model B enforcement) when voucher functionality
 * is enabled, without requiring vouchers for normal mint operations.
 *
 * <h3>Model B Enforcement</h3>
 * <p>Model B vouchers can only be redeemed at the issuing merchant, not at the mint.
 * This detector is used by both swap and melt operations to reject voucher proofs.
 */
final class VoucherSecretDetector {
    private VoucherSecretDetector() {
        // Utility class - prevent instantiation
    }

    /**
     * Checks if a secret is a VoucherSecret instance.
     *
     * <p>Named for what it selects rather than for what it is. It answers <em>false</em> for a
     * {@code P2PK_VOUCHER}, which is a voucher — but one whose spending condition also requires
     * a witness. Callers choosing a spending condition want that distinction; callers asking
     * "is this a voucher at all" want {@link #carriesVoucherMetadata(Secret)}.
     *
     * <p>Detects:
     * <ul>
     *   <li>VoucherSecret from cashu-lib-common (NUT-10 tag-based format)</li>
     *   <li>Any WellKnownSecret with VOUCHER kind</li>
     * </ul>
     *
     * @param secret the secret to check
     * @return true if the secret is an unlocked voucher, false otherwise
     */
    static boolean isUnlockedVoucherSecret(Secret secret) {
        if (secret == null) {
            return false;
        }
        // Check for VoucherSecret (NUT-10 tag-based format from cashu-lib-common)
        // This also catches VoucherWellKnownSecret which extends VoucherSecret
        if (secret instanceof VoucherSecret) {
            return true;
        }
        // Check for WellKnownSecret with VOUCHER kind (fallback for deserialized secrets)
        if (secret instanceof WellKnownSecret wks && wks.getKind() == WellKnownSecret.Kind.VOUCHER) {
            return true;
        }
        return false;
    }

    /**
     * Checks if a secret is a P2PK-locked voucher.
     *
     * <p>Deliberately <em>not</em> folded into {@link #isUnlockedVoucherSecret(Secret)}. That
     * method selects the voucher-only spending condition, which never checks a witness;
     * answering true there would send a locked voucher down a path that ignores its lock — the
     * failure the {@code P2PK_VOUCHER} kind exists to prevent.
     *
     * @param secret the secret to check
     * @return true if the secret is a P2PK-locked voucher, false otherwise
     */
    static boolean isP2PKVoucherSecret(Secret secret) {
        return secret instanceof WellKnownSecret wks
                && wks.getKind() == WellKnownSecret.Kind.P2PK_VOUCHER;
    }

    /**
     * Checks if a secret carries voucher metadata, under either voucher kind.
     *
     * <p>The honest "is this a voucher" question, for rules that are about what a proof
     * <em>is</em> rather than which spending condition it needs — Model B redemption and the
     * mixed-proof-types rule both want this one.
     *
     * @param secret the secret to check
     * @return true if the secret is a voucher of either kind, false otherwise
     */
    static boolean carriesVoucherMetadata(Secret secret) {
        return isUnlockedVoucherSecret(secret) || isP2PKVoucherSecret(secret);
    }
}

@Slf4j
@AllArgsConstructor
public class VerifyProofsTask<T extends Secret> extends InstrumentedTask<Void> {

    private final Mint mint;
    private final PostSwapRequest<T> request;
    private final MintProtocolService mintProtocolService;

    @Override
    protected Void doExecute() throws CashuErrorException {

        log.info("Verifying proofs....");
        validateAmounts();
        verifyProofs(request);

        return null;
    }

    /**
     * Checks that every amount in the request is a positive integer.
     *
     * <p>The transaction balance is deliberately not checked here. NUT-02 states one
     * equation, {@code sum(inputs) - fees == sum(outputs)}, and {@link VerifyFeesTask}
     * enforces it. A second, fee-unaware equation here contradicted it for any keyset
     * with a non-zero {@code input_fee_ppk}.
     */
    private void validateAmounts() throws CashuErrorException {
        log.debug("Validate Amounts...");

        for (Proof<T> proof : request.getInputs()) {
            requirePositiveAmount(proof.getAmount());
        }
        for (BlindedMessage blindedMessage : request.getBlindedMessages()) {
            requirePositiveAmount(blindedMessage.getAmount());
        }
        log.info("validate_amounts_ok");
    }

    private void requirePositiveAmount(int amount) throws CashuErrorException {
        if (amount <= 0) {
            log.error("validate_amounts_error amount={}", amount);
            throw new CashuErrorException(CashuErrorCode.validate_amounts_error);
        }
    }

    private void verifyProofs(@NonNull PostSwapRequest<T> request) throws CashuErrorException {
        log.debug("Verify proofs: {}", request.getInputs());
        List<Proof<T>> proofs = request.getInputs();
        List<BlindedMessage> blindedMessages = request.getBlindedMessages();

        // Did this mint issue these proofs at all? Asked here, unconditionally, for every input,
        // before any spending condition is chosen.
        //
        // This used to be the spending conditions' job, and P2PKSpendingCondition did not do it:
        // a P2PK condition checks the witness, and nothing in that responsibility suggests it
        // should also be checking the mint's own signature. The result was that a plain P2PK
        // input was never verified against a keyset key at all, so a secret locked to an
        // attacker's own key with arbitrary bytes in C bought real signed outputs. Making it the
        // task's job rather than each condition's means a future condition can add requirements
        // but cannot drop this one.
        ProofAuthenticity authenticity = new ProofAuthenticity(mint, mintProtocolService);
        for (Proof<T> proof : proofs) {
            authenticity.require(proof);
        }

        // NUT-11 SIG_ALL signs one message over the whole swap, so the condition needs the
        // transaction, not just this proof's outputs.
        P2PKTransaction transaction = P2PKTransaction.forSwap(proofs, blindedMessages);
        for (Proof<T> proof : proofs) {
            Secret secret = proof.getSecret();
            SpendingCondition<T> spendingCondition = getSpendingCondition(secret, transaction);
            spendingCondition.verify(proof);
        }

        log.info("Verify proofs ok");
    }

    private SpendingCondition<T> getSpendingCondition(@NonNull Secret secret, @NonNull P2PKTransaction transaction)
            throws CashuErrorException {
        // Voucher proofs use standard keyset keys (same as RSS) plus voucher-specific validations
        // Model B enforcement (merchant-only redemption) belongs at the application layer, not here
        // Swapping is NOT redemption - it's essential for double-spend prevention and P2P transfers
        // MUST come before both branches below. P2PKVoucherSecret extends P2PKSecret, and a
        // P2PK_VOUCHER is a voucher, so either of the following branches would match it and
        // run only half its conditions: the voucher branch never checks the witness, and the
        // P2PK branch never checks the issuer signature or expiry. Both failures are silent.
        if (VoucherSecretDetector.isP2PKVoucherSecret(secret)) {
            log.debug("P2PK-locked voucher detected in swap - verifying voucher conditions and the lock");
            return (SpendingCondition<T>) new P2PKVoucherSpendingCondition<>(
                    mint, mintProtocolService, transaction);
        }
        if (VoucherSecretDetector.isUnlockedVoucherSecret(secret)) {
            log.debug("Voucher secret detected in swap - using VoucherSpendingCondition with standard keyset keys");
            return (SpendingCondition<T>) new VoucherSpendingCondition<>(mint, mintProtocolService);
        }
        if (secret instanceof P2PKSecret) {
            return (SpendingCondition<T>) new P2PKSpendingCondition(transaction);
        }
        if (secret instanceof RandomStringSecret) {
            return (SpendingCondition<T>) new RSSSpendingCondition(mint, mintProtocolService);
        }
        log.error("Unsupported proof type in swap request: {}", secret.getClass().getName());
        throw new CashuErrorException(CashuErrorCode.unsupported_proof_type,
                "Unsupported proof type for swap: " + secret.getClass().getSimpleName()
        );
    }
}
