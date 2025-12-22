package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.VoucherWellKnownSecret;
import xyz.tcheeric.cashu.common.WellKnownSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;

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
    private static final String VOUCHER_SECRET_CLASS = "xyz.tcheeric.cashu.voucher.domain.VoucherSecret";

    private VoucherSecretDetector() {
        // Utility class - prevent instantiation
    }

    /**
     * Checks if a secret is a VoucherSecret instance.
     *
     * <p>This method detects voucher secrets in multiple forms:
     * <ul>
     *   <li>VoucherSecret from cashu-voucher-domain (optional dependency)</li>
     *   <li>VoucherWellKnownSecret from cashu-lib-common (NUT-10 format)</li>
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
        // Check for VoucherWellKnownSecret (NUT-10 format from cashu-lib-common)
        if (secret instanceof VoucherWellKnownSecret) {
            return true;
        }
        // Check for WellKnownSecret with VOUCHER kind
        if (secret instanceof WellKnownSecret wks && wks.getKind() == WellKnownSecret.Kind.VOUCHER) {
            return true;
        }
        // Check for VoucherSecret from cashu-voucher-domain (optional dependency)
        // This works even if the class is loaded optionally
        return VOUCHER_SECRET_CLASS.equals(secret.getClass().getName());
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

    private void validateAmounts() throws CashuErrorException {
        log.debug("Validate Amounts...");
        var proofs = request.getInputs();
        var blindedMessages = request.getBlindedMessages();

        int proofsAmount = proofs.stream().mapToInt(Proof::getAmount).sum();
        int blindedMessagesAmount = blindedMessages.stream().mapToInt(BlindedMessage::getAmount).sum();

        if (proofsAmount != blindedMessagesAmount) {
            log.error("validate_amounts_error");
            ErrorResponse error = new ErrorResponse("validate_amounts_error");
            throw new CashuErrorException(error.toJson());
        }
        log.info("validate_amounts_ok");
    }

    private void verifyProofs(@NonNull PostSwapRequest<T> request) throws CashuErrorException {
        log.debug("Verify proofs: {}", request.getInputs());
        List<Proof<T>> proofs = request.getInputs();
        List<BlindedMessage> blindedMessages = request.getBlindedMessages();

        for (Proof<T> proof : proofs) {
            Secret secret = proof.getSecret();
            SpendingCondition<T> spendingCondition = getSpendingCondition(secret, blindedMessages);
            spendingCondition.verify(proof);
        }

        log.info("Verify proofs ok");
    }

    private SpendingCondition<T> getSpendingCondition(@NonNull Secret secret, List<BlindedMessage> blindedMessages)
            throws CashuErrorException {
        if (VoucherSecretDetector.isVoucherSecret(secret)) {
            log.warn("Voucher secret detected in swap request - rejecting per Model B");
            ErrorResponse error = new ErrorResponse(
                    "voucher_swap_rejected",
                    "Voucher proofs cannot be swapped at the mint (Model B - redeem with merchant)."
            );
            throw new CashuErrorException(error.toJson());
        }
        if (secret instanceof P2PKSecret) {
            return (SpendingCondition<T>) new P2PKSpendingCondition(blindedMessages);
        }
        if (secret instanceof RandomStringSecret || secret instanceof VoucherWellKnownSecret) {
            return (SpendingCondition<T>) new RSSSpendingCondition(mint, mintProtocolService);
        }
        log.error("Unsupported proof type in swap request: {}", secret.getClass().getName());
        ErrorResponse error = new ErrorResponse(
                "unsupported_proof_type",
                "Unsupported proof type for swap: " + secret.getClass().getSimpleName()
        );
        throw new CashuErrorException(error.toJson());
    }
}
