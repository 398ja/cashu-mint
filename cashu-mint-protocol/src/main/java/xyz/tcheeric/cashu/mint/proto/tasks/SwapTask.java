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
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.IouKeysets;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SecurityLimits;

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

        // Security limit checks (per Oracle Secure Coding Guidelines DOS-1)
        List<Proof<T>> inputProofs = request.getInputs();
        List<BlindedMessage> outputMessages = request.getBlindedMessages();

        if (inputProofs != null && inputProofs.size() > SecurityLimits.MAX_PROOFS) {
            log.warn("swap_task too_many_inputs count={} max={}",
                    inputProofs.size(), SecurityLimits.MAX_PROOFS);
            ErrorResponse error = new ErrorResponse("too_many_inputs",
                    "Maximum " + SecurityLimits.MAX_PROOFS + " inputs allowed");
            throw new CashuErrorException(error.toJson());
        }

        if (outputMessages != null && outputMessages.size() > SecurityLimits.MAX_BLINDED_MESSAGES) {
            log.warn("swap_task too_many_outputs count={} max={}",
                    outputMessages.size(), SecurityLimits.MAX_BLINDED_MESSAGES);
            ErrorResponse error = new ErrorResponse("too_many_outputs",
                    "Maximum " + SecurityLimits.MAX_BLINDED_MESSAGES + " outputs allowed");
            throw new CashuErrorException(error.toJson());
        }

        Mint mint = mintLoadService.load(mintId, false);
        if (mint == null) {
            log.error("Mint not found");
            ErrorResponse error = new ErrorResponse("swap_mint_not_found");
            throw new CashuErrorException(error.toJson());
        }

        // Dalia Phase 9: zero-value IOU proofs cannot be swapped (issuance + checkstate only), and
        // nothing may be swapped into the IOU keyset. Checked across ALL inputs and outputs.
        refuseIouSwap(mint, inputProofs, outputMessages);

        // Acquire per-proof locks to prevent concurrent swaps of the same proofs.
        // This serializes access similar to SERIALIZABLE transaction isolation.
        List<Proof<T>> proofsToSwap = request.getInputs();
        try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(
                proofsToSwap.stream().map(proof -> proof.getSecret().toString()).toList())) {

            MintProtocolService service = MintProtocolServiceFactory.getInstance();

            // Validate no mixed voucher/regular proofs before verification
            boolean isVoucherSwap = validateNoMixedProofTypes(proofsToSwap);

            // Validate voucher swap amounts before signing (free splitting, but totals must match)
            if (isVoucherSwap) {
                validateVoucherSwapAmounts(proofsToSwap, request.getBlindedMessages());
            }

            new VerifyProofsTask<>(mint, request, service).execute();

            // Voucher swaps use standard keyset keys (power-of-2 amounts)
            // The voucher metadata is stored in the secret's NUT-10 tags, not affecting the keys
            List<BlindSignature> blindSignatures = new ArrayList<>(request.getBlindedMessages().size());
            for (BlindedMessage bm : request.getBlindedMessages()) {
                SignBlindedMessageTask signTask = new SignBlindedMessageTask(mint, bm, service, signatureVaultService);
                BlindSignature sig = signTask.execute();
                blindSignatures.add(sig);
            }

            PostSwapResponse response = new PostSwapResponse(blindSignatures);

            // Skip fee verification for voucher swaps (no fees apply)
            if (!isVoucherSwap) {
                new VerifyFeesTask<>(request, response, mintLoadService).execute();
            }
            new InvalidateProofsTask<>(mint, proofsToSwap).execute();

            return response;
        }
    }

    /**
     * Validates that the swap request does not mix voucher and regular proofs.
     * Mixing proof types in the same swap operation is not allowed.
     *
     * @param proofs the list of proofs to validate
     * @return true if all proofs are voucher proofs (voucher swap), false otherwise
     * @throws CashuErrorException if mixed proof types are detected
     */
    /** Refuse any swap that touches the zero-value IOU keyset (input proofs or output denominations). */
    private void refuseIouSwap(Mint mint, List<Proof<T>> inputs, List<BlindedMessage> outputs)
            throws CashuErrorException {
        if (inputs != null) {
            for (Proof<T> proof : inputs) {
                if (isIouKeysetId(mint, String.valueOf(proof.getKeySetId()))) {
                    throw new CashuErrorException(new ErrorResponse(
                            "iou_not_swappable", "Zero-value IOU tokens cannot be swapped.").toJson());
                }
            }
        }
        if (outputs != null) {
            for (BlindedMessage output : outputs) {
                if (isIouKeysetId(mint, String.valueOf(output.getKeySetId()))) {
                    throw new CashuErrorException(new ErrorResponse(
                            "iou_not_swappable", "Cannot swap into the zero-value IOU keyset.").toJson());
                }
            }
        }
    }

    private boolean isIouKeysetId(Mint mint, String keySetId) {
        if (keySetId == null || mint.getKeySets() == null) {
            return false;
        }
        return mint.getKeySets().stream()
                .filter(ks -> keySetId.equals(ks.getId()))
                .findFirst()
                .map(IouKeysets::isIouKeyset)
                .orElse(false);
    }

    private boolean validateNoMixedProofTypes(List<Proof<T>> proofs) throws CashuErrorException {
        if (proofs == null || proofs.isEmpty()) {
            return false;
        }

        // Single pass to count voucher proofs instead of multiple stream iterations
        long voucherCount = proofs.stream().filter(this::isVoucherProof).count();
        boolean hasVoucherProofs = voucherCount > 0;
        boolean hasRegularProofs = voucherCount < proofs.size();

        if (hasVoucherProofs && hasRegularProofs) {
            log.warn("swap_task mixed_proof_types_rejected voucher_count={} regular_count={}",
                    voucherCount, proofs.size() - voucherCount);
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
