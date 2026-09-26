package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.IouKeysets;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SecurityLimits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task executing a swap of proofs for new blinded signatures.
 *
 * <p>The swap takes an exclusive {@link SwapProofHold} on its inputs before it signs anything,
 * commits that hold once the outputs are signed, and releases it if the swap fails before
 * signing. Signing first and spending afterwards would let a failure in between leave outputs
 * recoverable through NUT-09 restore while the inputs were still spendable (issue #400).
 */
@Slf4j
public class SwapTask<T extends Secret> extends InstrumentedTask<PostSwapResponse> {

    private final UUID mintId;
    private final PostSwapRequest<T> request;
    private final MintLoadService mintLoadService;
    private final SignatureVaultService signatureVaultService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;
    private final SwapHoldRepository swapHoldRepository;

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(mintId, request, new DefaultMintLoadService(), signatureVaultService);
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(mintId, request, mintLoadService, signatureVaultService,
                new DefaultMintVaultService(), new DefaultProofVaultService());
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull SignatureVaultService signatureVaultService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService) {
        this(mintId, request, mintLoadService, signatureVaultService, mintVaultService,
                proofVaultService, MintIntegrityContext.swapHoldRepository());
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull SignatureVaultService signatureVaultService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService,
                    @NonNull SwapHoldRepository swapHoldRepository) {
        this.mintId = mintId;
        this.request = request;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
        this.swapHoldRepository = swapHoldRepository;
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
                    throw new CashuErrorException(CashuErrorCode.too_many_inputs,
                    "Maximum " + SecurityLimits.MAX_PROOFS + " inputs allowed");
        }

        if (outputMessages != null && outputMessages.size() > SecurityLimits.MAX_BLINDED_MESSAGES) {
            log.warn("swap_task too_many_outputs count={} max={}",
                    outputMessages.size(), SecurityLimits.MAX_BLINDED_MESSAGES);
                    throw new CashuErrorException(CashuErrorCode.too_many_outputs,
                    "Maximum " + SecurityLimits.MAX_BLINDED_MESSAGES + " outputs allowed");
        }

        Mint mint = mintLoadService.load(mintId, false);
        if (mint == null) {
            log.error("Mint not found");
            throw new CashuErrorException(CashuErrorCode.swap_mint_not_found);
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

            // This is the keyset snapshot scope boundary for a swap. It is created once here and
            // handed to every task below that needs keysets, so one HTTP request reads each
            // generation at most once. The alternative, each task calling
            // KeySetDirectory.of(mintLoadService) for itself, is what left staging at a measured
            // 22.5 keyset loads and 87 vault key GETs per swap after the per-task directory
            // landed: correct in isolation, still repeated per task.
            //
            // The reference dies with this stack frame, which is entered once per POST /v1/swap,
            // so a keyset rotation is invisible for at most one request. That is safe because it
            // is also required: the unit rules, the archived-keyset rule and the fee arithmetic
            // must judge every input and output of this swap against one set of keysets, and a
            // rotation landing mid-swap would otherwise let them disagree. Nothing static, no
            // ThreadLocal and no Spring scope, so a pooled handler thread carries nothing into
            // the next request.
            KeySetDirectory keySets = KeySetDirectory.of(mintLoadService);

            // Validate no mixed voucher/regular proofs before verification
            boolean isVoucherSwap = validateNoMixedProofTypes(proofsToSwap);

            // Validate voucher swap amounts before signing (free splitting, but totals must match)
            if (isVoucherSwap) {
                validateVoucherSwapAmounts(proofsToSwap, request.getBlindedMessages());
            }

            new ValidateTransactionTask<>(proofsToSwap, request.getBlindedMessages(),
                    keySets, signatureVaultService).execute();

            new VerifyProofsTask<>(mint, request, service).execute();

            // NUT-02: the balance equation is checked before signing, so a rejected swap
            // leaves no blind signature behind for NUT-09 restore to hand back. Voucher
            // swaps carry no fees and were balanced above.
            if (!isVoucherSwap) {
                new VerifyFeesTask<>(request, keySets).execute();
            }

            return signAgainstHeldInputs(mint, proofsToSwap, service);
        }
    }

    /**
     * Signs the outputs between taking and committing an exclusive hold on the inputs.
     *
     * <p>This ordering is what makes the swap safe to fail. Before signing, the inputs are held
     * and any failure releases them, so the wallet keeps its money and no signature exists.
     * After signing, the inputs are already unspendable by anyone else, so a failure can never
     * leave redeemable outputs alongside spendable inputs.
     *
     * <p>"Before signing" means before the first signature is recorded, not before the call that
     * failed. A failure on a later output, such as a concurrent swap recording the same blinded
     * message first ({@code outputs_already_signed}, issue #491), leaves the earlier outputs
     * durably signed and recoverable through NUT-09 restore. Releasing the inputs then would let
     * the same value be redeemed twice, so a partially signed swap spends its inputs instead.
     */
    private PostSwapResponse signAgainstHeldInputs(Mint mint,
                                                   List<Proof<T>> proofsToSwap,
                                                   MintProtocolService service)
            throws CashuErrorException {
        SwapProofHold hold =
                new SwapProofHold(mintId, mintVaultService, proofVaultService, swapHoldRepository);
        hold.claim(proofsToSwap);

        List<BlindSignature> blindSignatures = new ArrayList<>(request.getBlindedMessages().size());
        try {
            hold.markSigning();
            signOutputs(mint, service, blindSignatures);
        } catch (CashuErrorException | RuntimeException signingFailure) {
            resolveTheHoldAfterFailedSigning(hold, blindSignatures);
            throw signingFailure;
        }

        commitOrStrandTheHold(hold);
        return new PostSwapResponse(blindSignatures);
    }

    /**
     * Releases the inputs when nothing was signed, and spends them when anything was.
     *
     * @param hold               the hold on this swap's inputs
     * @param recordedSignatures the signatures recorded before the failure
     */
    private void resolveTheHoldAfterFailedSigning(SwapProofHold hold,
                                                  List<BlindSignature> recordedSignatures)
            throws CashuErrorException {
        if (recordedSignatures.isEmpty()) {
            hold.release();
            return;
        }
        log.error("[swap-hold][alert] SWAP_PARTIALLY_SIGNED mint_id={} signed_outputs={} {}",
                mintId, recordedSignatures.size(), hold.describeStrandedHold());
        commitOrStrandTheHold(hold);
    }

    /**
     * Voucher swaps use standard keyset keys; the voucher metadata lives in the secret's NUT-10
     * tags and does not affect which key signs.
     *
     * @param signed receives each signature as soon as it is recorded, so a caller that sees
     *               this fail can tell how far it got
     */
    private void signOutputs(Mint mint, MintProtocolService service, List<BlindSignature> signed)
            throws CashuErrorException {
        for (BlindedMessage output : request.getBlindedMessages()) {
            signed.add(new SignBlindedMessageTask(mint, output, service, signatureVaultService,
                    SignatureSource.SWAP).execute());
        }
    }

    /**
     * Spends the held inputs, or leaves the hold standing for an operator to resolve.
     *
     * <p>The outputs are signed and durable by now, so the hold must never be released here.
     * Leaving the inputs {@code PENDING} and bound keeps them unspendable, which is what stops
     * the value being redeemed twice, and keeps the swap resolvable rather than lost.
     *
     * <p>Whatever the vault reported, the client is told {@code proofs_pending}: that is the
     * state their inputs are actually in, and it tells a wallet to wait for the swap to be
     * resolved rather than to treat the inputs as spendable and try to spend them elsewhere.
     */
    private void commitOrStrandTheHold(SwapProofHold hold) throws CashuErrorException {
        try {
            hold.commit();
        } catch (CashuErrorException | RuntimeException commitFailure) {
            log.error("[swap-hold][alert] SWAP_SIGNED_COMMIT_FAILED mint_id={} {}",
                    mintId, hold.describeStrandedHold(), commitFailure);
            CashuErrorException stranded = new CashuErrorException(CashuErrorCode.proofs_pending,
                    hold.describeStrandedHold());
            stranded.initCause(commitFailure);
            throw stranded;
        }
    }

    /**
     * Refuse any swap that touches the zero-value IOU keyset (input proofs or output denominations).
     *
     * <p>The mint's keysets are read once here and every input and output judged against that one
     * set. Re-deriving it per item is what made this scale with the request: see
     * {@link KeySetDirectory#of(MintLoadService)} for the measured cost.
     */
    private void refuseIouSwap(Mint mint, List<Proof<T>> inputs, List<BlindedMessage> outputs)
            throws CashuErrorException {
        Set<String> iouKeySetIds = iouKeySetIds(mint);
        if (iouKeySetIds.isEmpty()) {
            return;
        }
        if (inputs != null) {
            for (Proof<T> proof : inputs) {
                if (iouKeySetIds.contains(String.valueOf(proof.getKeySetId()))) {
                    throw new CashuErrorException(CashuErrorCode.iou_not_swappable, "Zero-value IOU tokens cannot be swapped.");
                }
            }
        }
        if (outputs != null) {
            for (BlindedMessage output : outputs) {
                if (iouKeySetIds.contains(String.valueOf(output.getKeySetId()))) {
                    throw new CashuErrorException(CashuErrorCode.iou_not_swappable, "Cannot swap into the zero-value IOU keyset.");
                }
            }
        }
    }

    /** The IOU keyset ids this mint knows, indexed once so the checks above are pure lookups. */
    private Set<String> iouKeySetIds(Mint mint) {
        if (mint.getKeySets() == null) {
            return Set.of();
        }
        Set<String> iouKeySetIds = new HashSet<>();
        for (KeySet keySet : mint.getKeySets()) {
            if (keySet != null && keySet.getId() != null && IouKeysets.isIouKeyset(keySet)) {
                iouKeySetIds.add(keySet.getId());
            }
        }
        return iouKeySetIds;
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

        // Single pass to count voucher proofs instead of multiple stream iterations
        long voucherCount = proofs.stream().filter(this::isVoucherProof).count();
        boolean hasVoucherProofs = voucherCount > 0;
        boolean hasRegularProofs = voucherCount < proofs.size();

        if (hasVoucherProofs && hasRegularProofs) {
            log.warn("swap_task mixed_proof_types_rejected voucher_count={} regular_count={}",
                    voucherCount, proofs.size() - voucherCount);
                    throw new CashuErrorException(CashuErrorCode.mixed_proof_types_error,
                    "Cannot mix voucher and regular proofs in same operation");
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
            throw new CashuErrorException(CashuErrorCode.voucher_split_amount_mismatch,
                    String.format("Voucher split amounts must match: input=%d output=%d", totalInput, totalOutput));
        }

        // Validate all outputs have positive amounts
        for (BlindedMessage output : outputs) {
            if (output.getAmount() <= 0) {
                throw new CashuErrorException(CashuErrorCode.invalid_output_amount,
                        "Output amounts must be positive");
            }
        }

        log.info("swap_task voucher_split_validated input={} outputs={}", totalInput,
                outputs.stream().map(BlindedMessage::getAmount).toList());
    }

    /**
     * Checks if a proof contains a voucher secret, under either voucher kind.
     *
     * <p>Includes P2PK-locked vouchers. This feeds the mixed-proof-types rule, which is about
     * what a proof <em>is</em> rather than how it is locked — a locked voucher mixed with
     * regular proofs is the same modelling error as an unlocked one, and answering false here
     * would let the mix through.
     *
     * @param proof the proof to check
     * @return true if the proof has a voucher secret, false otherwise
     */
    private boolean isVoucherProof(Proof<T> proof) {
        if (proof == null || proof.getSecret() == null) {
            return false;
        }
        return VoucherSecretDetector.carriesVoucherMetadata(proof.getSecret());
    }
}
