package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.VoucherMasterSecretConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task executing a swap of proofs for new blinded signatures.
 */
@Slf4j
public class SwapTask<T extends Secret> extends InstrumentedTask<PostSwapResponse> {

    private final UUID mintId;
    private final PostSwapRequest<T> request;
    private final MintLoadService mintLoadService;
    private final SignatureVaultService signatureVaultService;

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(mintId, request, new DefaultMintLoadService(), signatureVaultService);
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this.mintId = mintId;
        this.request = request;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
    }

    @Override
    protected PostSwapResponse doExecute() throws CashuErrorException {
        log.debug("Executing SwapTask for mint {}", mintId);
        Mint mint = mintLoadService.load(mintId, false);
        if (mint == null) {
            log.error("Mint not found");
            ErrorResponse error = new ErrorResponse("swap_mint_not_found");
            throw new CashuErrorException(error.toJson());
        }

        MintProtocolService service = MintProtocolServiceFactory.getInstance();

        // Validate no mixed voucher/regular proofs before verification
        boolean isVoucherSwap = validateNoMixedProofTypes(request.getInputs());

        new VerifyProofsTask<>(mint, request, service).execute();

        // Voucher swaps allow arbitrary output amounts (free splitting)
        if (isVoucherSwap) {
            validateVoucherSwapAmounts(request.getInputs(), request.getBlindedMessages());
        }

        // Get voucher master secret if in voucher mode
        String voucherSecret = isVoucherSwap ? VoucherMasterSecretConfig.getMasterSecret() : null;

        List<BlindSignature> blindSignatures = new ArrayList<>();
        for (BlindedMessage bm : request.getBlindedMessages()) {
            SignBlindedMessageTask signTask = isVoucherSwap
                    ? new SignBlindedMessageTask(mint, bm, service, signatureVaultService, true, voucherSecret)
                    : new SignBlindedMessageTask(mint, bm, service, signatureVaultService);
            BlindSignature sig = signTask.execute();
            blindSignatures.add(sig);
        }

        PostSwapResponse response = new PostSwapResponse(blindSignatures);

        // Skip fee verification for voucher swaps (no fees apply)
        if (!isVoucherSwap) {
            new VerifyFeesTask<>(request, response, mintLoadService).execute();
        }
        new InvalidateProofsTask<>(mint, request.getInputs()).execute();

        return response;
    }

    /**
     * Validates that the swap request does not mix voucher and regular proofs.
     * Mixing proof types in the same swap operation is not allowed.
     *
     * @param proofs the list of proofs to validate
     * @return true if all proofs are voucher proofs (voucher swap), false otherwise
     * @throws CashuErrorException if mixed proof types are detected
     */
    private boolean validateNoMixedProofTypes(List<Proof<T>> proofs) throws CashuErrorException {
        if (proofs == null || proofs.isEmpty()) {
            return false;
        }

        boolean hasVoucherProofs = proofs.stream().anyMatch(this::isVoucherProof);
        boolean hasRegularProofs = proofs.stream().anyMatch(proof -> !isVoucherProof(proof));

        if (hasVoucherProofs && hasRegularProofs) {
            log.warn("swap_task mixed_proof_types_rejected voucher_count={} regular_count={}",
                    proofs.stream().filter(this::isVoucherProof).count(),
                    proofs.stream().filter(proof -> !isVoucherProof(proof)).count());
            ErrorResponse error = new ErrorResponse("mixed_proof_types_error",
                    "Cannot mix voucher and regular proofs in same operation");
            throw new CashuErrorException(error.toJson());
        }

        if (hasVoucherProofs) {
            log.debug("swap_task voucher_only_swap proof_count={}", proofs.size());
        }

        return hasVoucherProofs;
    }

    /**
     * Validates voucher swap amounts.
     * Vouchers allow arbitrary output amounts (free splitting), but total must match.
     *
     * @param inputs the input proofs
     * @param outputs the output blinded messages
     * @throws CashuErrorException if amounts don't match
     */
    private void validateVoucherSwapAmounts(List<Proof<T>> inputs, List<BlindedMessage> outputs) throws CashuErrorException {
        long totalInput = inputs.stream().mapToLong(Proof::getAmount).sum();
        long totalOutput = outputs.stream().mapToLong(BlindedMessage::getAmount).sum();

        if (totalOutput != totalInput) {
            log.warn("swap_task voucher_amount_mismatch input={} output={}", totalInput, totalOutput);
            ErrorResponse error = new ErrorResponse("voucher_split_amount_mismatch",
                    String.format("Voucher split amounts must match: input=%d output=%d", totalInput, totalOutput));
            throw new CashuErrorException(error.toJson());
        }

        // Validate all outputs have positive amounts
        for (BlindedMessage output : outputs) {
            if (output.getAmount() <= 0) {
                ErrorResponse error = new ErrorResponse("invalid_output_amount",
                        "Output amounts must be positive");
                throw new CashuErrorException(error.toJson());
            }
        }

        log.info("swap_task voucher_split_validated input={} outputs={}", totalInput,
                outputs.stream().map(BlindedMessage::getAmount).toList());
    }

    /**
     * Checks if a proof contains a voucher secret.
     *
     * @param proof the proof to check
     * @return true if the proof has a voucher secret, false otherwise
     */
    private boolean isVoucherProof(Proof<T> proof) {
        if (proof == null || proof.getSecret() == null) {
            return false;
        }
        return VoucherSecretDetector.isVoucherSecret(proof.getSecret());
    }
}
