package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

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
     * @param secret the secret to check
     * @return true if the secret is a VoucherSecret, false otherwise
     */
    static boolean isVoucherSecret(Secret secret) {
        if (secret == null) {
            return false;
        }
        // Check if the secret is an instance of VoucherSecret
        // This works even if the class is loaded optionally
        return VOUCHER_SECRET_CLASS.equals(secret.getClass().getName());
    }
}

@Slf4j
@AllArgsConstructor
public class VerifyProofsTask<T extends Secret> implements Task<Void> {

    private final Mint mint;
    private final PostSwapRequest<T> request;
    private final MintProtocolService mintProtocolService;

    @Override
    public Void execute() throws CashuErrorException {

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

            // Model B enforcement: Reject voucher secrets
            // Vouchers can only be redeemed at the issuing merchant, not at the mint
            if (VoucherSecretDetector.isVoucherSecret(secret)) {
                log.warn("Voucher secret rejected in swap operation (Model B enforcement)");
                ErrorResponse error = new ErrorResponse(
                    "voucher_not_accepted",
                    "Vouchers cannot be redeemed at mint (Model B). " +
                    "Please redeem with issuing merchant."
                );
                throw new CashuErrorException(error.toJson());
            }

            SpendingCondition<T> spendingCondition = getSpendingCondition(secret, blindedMessages);
            spendingCondition.verify(proof);
        }

        log.info("Verify proofs ok");
    }

    private SpendingCondition<T> getSpendingCondition(@NonNull Secret secret, List<BlindedMessage> blindedMessages) {
        if (secret instanceof P2PKSecret) {
            return (SpendingCondition<T>) new P2PKSpendingCondition(blindedMessages);
        }
        if (secret instanceof RandomStringSecret) {
            return (SpendingCondition<T>) new RSSSpendingCondition(mint, mintProtocolService);
        }
        throw new IllegalArgumentException("Unsupported proof type");
    }
}
