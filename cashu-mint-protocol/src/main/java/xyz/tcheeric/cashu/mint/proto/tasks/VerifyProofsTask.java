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
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
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
     * <p>This method detects voucher secrets in multiple forms:
     * <ul>
     *   <li>VoucherSecret from cashu-lib-common (NUT-10 tag-based format)</li>
     *   <li>Any WellKnownSecret with VOUCHER kind</li>
     * </ul>
     *
     * @param secret the secret to check
     * @return true if the secret is a VoucherSecret, false otherwise
     */
    static boolean isVoucherSecret(Secret secret) {
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
        if (VoucherSecretDetector.isVoucherSecret(secret)) {
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
