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
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
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
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherRedemptionCap;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherDocumentContext;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionCap;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherTerms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task executing a swap of proofs for new blinded signatures.
 *
 * <p>The swap takes an exclusive {@link SwapProofHold} on its inputs before it signs anything,
 * commits that hold once the outputs are signed, and releases it if the swap fails before
 * signing. Signing first and spending afterwards would let a failure in between leave outputs
 * recoverable through NUT-09 restore while the inputs were still spendable (issue #400).
 *
 * <p>A successful swap is remembered under its request fingerprint, and the identical request
 * arriving again is answered with the same signatures instead of being processed (NUT-19, issue
 * #482). See {@link SwapResponseReplay}.
 */
@Slf4j
public class SwapTask<T extends Secret> extends InstrumentedTask<PostSwapResponse> {

    /**
     * The refusals a replay can meet while its original is still in flight on another instance:
     * outputs the original already signed, inputs it holds, and inputs it has just spent. Anything
     * else is a refusal the original would have met too, so there is nothing to wait for.
     */
    private static final Set<CashuErrorCode> REFUSALS_OF_A_RACING_REPLAY = Set.of(
            CashuErrorCode.outputs_already_signed,
            CashuErrorCode.proofs_not_bound,
            CashuErrorCode.proofs_pending,
            CashuErrorCode.verify_proof_already_used_error);

    private final UUID mintId;
    private final PostSwapRequest<T> request;
    private final MintLoadService mintLoadService;
    private final SignatureVaultService signatureVaultService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;
    private final SwapHoldRepository swapHoldRepository;
    private final SwapResponseCache swapResponseCache;
    private final InFlightReplayWait inFlightReplayWait;

    /** The raw {@code X-Cashu-Voucher-Redemption} header (cashu-mint#540), or null. */
    private String rawRedemption;
    private String redeemerToken;
    private VoucherRedemptionCap redemptionCap;

    /** Whether any output of this swap has been signed: a claim is only given back if not. */
    private boolean signedAnything;

    /** The request fingerprint of a declared swap whose claim stands, once it has succeeded. */
    private String redemptionReceipt;

    /**
     * Attaches a redeemer's declaration that this swap credits a voucher (cashu-mint#540). The
     * headers are passed raw: whether they are read, and what a malformed or untrusted one does,
     * depends on the cap's mode.
     *
     * @param rawDeclaration the {@code X-Cashu-Voucher-Redemption} header, or null
     * @param redeemerToken  the {@code X-Cashu-Voucher-Redeemer} header, or null
     * @return this task
     */
    public SwapTask<T> declaringRedemption(String rawDeclaration, String redeemerToken) {
        this.rawRedemption = rawDeclaration;
        this.redeemerToken = redeemerToken;
        return this;
    }

    /**
     * The receipt of a declared redemption: the swap's NUT-19 request fingerprint, which the
     * redeemer presents to release the declaration if the credit is later undone.
     *
     * @return the receipt, or empty when this swap made no standing claim
     */
    public Optional<String> redemptionReceipt() {
        return Optional.ofNullable(redemptionReceipt);
    }

    /** For tests: the cap to apply instead of the installed one. */
    SwapTask<T> withRedemptionCap(VoucherRedemptionCap cap) {
        this.redemptionCap = cap;
        return this;
    }

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
        this(mintId, request, mintLoadService, signatureVaultService, mintVaultService,
                proofVaultService, swapHoldRepository, MintIntegrityContext.swapResponseCache());
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull SignatureVaultService signatureVaultService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService,
                    @NonNull SwapHoldRepository swapHoldRepository,
                    @NonNull SwapResponseCache swapResponseCache) {
        this(mintId, request, mintLoadService, signatureVaultService, mintVaultService,
                proofVaultService, swapHoldRepository, swapResponseCache, InFlightReplayWait.DEFAULT);
    }

    SwapTask(@NonNull UUID mintId,
             @NonNull PostSwapRequest<T> request,
             @NonNull MintLoadService mintLoadService,
             @NonNull SignatureVaultService signatureVaultService,
             @NonNull MintVaultService mintVaultService,
             @NonNull ProofVaultService proofVaultService,
             @NonNull SwapHoldRepository swapHoldRepository,
             @NonNull SwapResponseCache swapResponseCache,
             @NonNull InFlightReplayWait inFlightReplayWait) {
        this.mintId = mintId;
        this.request = request;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
        this.swapHoldRepository = swapHoldRepository;
        this.swapResponseCache = swapResponseCache;
        this.inFlightReplayWait = inFlightReplayWait;
    }

    @Override
    protected PostSwapResponse doExecute() throws CashuErrorException {
        log.debug("Executing SwapTask for mint {}", mintId);

        // Security limit checks (per Oracle Secure Coding Guidelines DOS-1)
        List<Proof<T>> inputProofs = request.getInputs();
        List<BlindedMessage> outputMessages = request.getBlindedMessages();

        SecurityLimits.requireWithinInputLimit("swap_task", inputProofs);
        SecurityLimits.requireWithinOutputLimit("swap_task", outputMessages);

        // cashu-voucher#64: the request's voucher documents are validated once, here, before any
        // other voucher work, and refused with voucher_document_invalid (90032). Each document is
        // hashed exactly once; everything below looks them up by key.
        VoucherDocumentContext voucherDocuments = VoucherDocumentContext.of(request);

        Mint mint = MintLoadService.loadForRedemption(mintLoadService, mintId);
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
        SwapResponseReplay replay =
                SwapResponseReplay.of(request, swapResponseCache, swapHoldRepository);
        SwapProofHold hold = new SwapProofHold(mintId, mintVaultService, proofVaultService,
                swapHoldRepository, replay.fingerprint());
        CashuErrorException racingRefusal;
        try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(
                proofsToSwap.stream().map(proof -> proof.getSecret().toString()).toList())) {

            // NUT-19: looked up under the input lock, so a replay racing the original on this
            // instance waits for it and then finds its response rather than failing on its hold.
            // It must come before validation, which would refuse the replay's spent inputs.
            Optional<PostSwapResponse> previous = replay.previousResponse();
            if (previous.isPresent()) {
                if (rawRedemption != null) {
                    // A declared redemption's replay answers with the same receipt, so a redeemer
                    // that lost the first reply can still release the declaration later.
                    redemptionReceipt = replay.fingerprint().hex();
                }
                return previous.get();
            }

            try {
                PostSwapResponse response = validateAndSign(mint, proofsToSwap, hold, replay, voucherDocuments);
                replay.remember(response);
                return response;
            } catch (CashuErrorException refusal) {
                if (!REFUSALS_OF_A_RACING_REPLAY.contains(refusal.getErrorCode())) {
                    throw refusal;
                }
                racingRefusal = refusal;
            }
        }
        // Outside the lock on purpose: a different request for the same inputs on this instance
        // must be refused at once, not queue behind this replay's wait (issue #519).
        return awaitOriginalOrRethrow(racingRefusal, hold, replay);
    }

    /**
     * Answers a refused attempt with the response of the same request in flight on another
     * instance, or rethrows the refusal when there is none (issue #519).
     *
     * <p>The input lock only serialises requests inside one JVM, so a wallet's replay can reach a
     * second instance while its original is still signing there. It then misses the cache and is
     * refused by the original's hold or spent inputs, although the swap is about to succeed. Only
     * a refusal of that kind is held back, and only while a hold recorded under the same request
     * fingerprint is in flight; a different request is refused at once.
     *
     * <p>A response borrowed from the original is not remembered again: it is already cached.
     */
    private PostSwapResponse awaitOriginalOrRethrow(CashuErrorException refusal, SwapProofHold hold,
                                                    SwapResponseReplay replay)
            throws CashuErrorException {
        Optional<PostSwapResponse> original =
                replay.awaitInFlightOriginal(hold.holdId(), inFlightReplayWait);
        if (original.isEmpty()) {
            throw refusal;
        }
        return original.get();
    }

    /**
     * Validates the swap and, only if every rule passes, signs its outputs against held inputs.
     */
    private PostSwapResponse validateAndSign(Mint mint, List<Proof<T>> proofsToSwap, SwapProofHold hold,
                                             SwapResponseReplay replay, VoucherDocumentContext voucherDocuments)
            throws CashuErrorException {
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

        // cashu-mint#540: a redeemer's declaration is checked before anything else, so a malformed
        // or unauthorised one costs nothing. Only enforce refuses; log ignores and counts it.
        VoucherRedemptionCap cap = redemptionCap != null ? redemptionCap : InstalledVoucherRedemptionCap.current();
        Optional<VoucherRedemptionCap.Authorised> declared = cap.authorise(rawRedemption, redeemerToken);
        Optional<VoucherTerms> redeemed = declared.isPresent()
                ? cap.match(declared.get(), proofsToSwap, voucherDocuments)
                : Optional.empty();

        new ValidateTransactionTask<>(proofsToSwap, request.getBlindedMessages(),
                keySets, signatureVaultService).execute();

        new VerifyProofsTask<>(mint, request, service, InstalledVoucherIssuerBinding.current(),
                voucherDocuments).execute();

        // NUT-02: the balance equation is checked before signing, so a rejected swap
        // leaves no blind signature behind for NUT-09 restore to hand back. Voucher
        // swaps carry no fees and were balanced above.
        if (!isVoucherSwap) {
            new VerifyFeesTask<>(request, keySets).execute();
        }

        Redemption redemption = redeemed.isPresent()
                ? new Redemption(cap, declared.get(), redeemed.get(), replay.fingerprint().hex())
                : null;
        return signAgainstHeldInputs(mint, proofsToSwap, service, hold, redemption);
    }

    /** A declared redemption to claim once the inputs are held (cashu-mint#540). */
    private record Redemption(VoucherRedemptionCap cap, VoucherRedemptionCap.Authorised declared,
                              VoucherTerms terms, String requestFingerprint) {
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
                                                   MintProtocolService service,
                                                   SwapProofHold hold,
                                                   Redemption redemption)
            throws CashuErrorException {
        hold.claim(proofsToSwap);

        // cashu-mint#540: the claim is taken only while this request holds the inputs (review
        // §2b). A racing replay of the same request on another instance is refused by the hold
        // above with proofs_not_bound and waits for the original, rather than meeting the cap and
        // being told 90025 for a swap that succeeded. The claim is recorded under the request
        // fingerprint and this hold (review §2a): a retry of the same request reuses it, and the
        // hold sweeper gives it back if this process dies before signing begins.
        if (redemption != null) {
            claimOrReleaseTheHold(redemption, hold);
        }

        List<BlindSignature> blindSignatures = new ArrayList<>(request.getBlindedMessages().size());
        try {
            hold.markSigning();
            signOutputs(mint, service, blindSignatures);
        } catch (CashuErrorException | RuntimeException signingFailure) {
            signedAnything = !blindSignatures.isEmpty();
            if (!signedAnything && redemption != null) {
                redemption.cap().giveBack(redemption.requestFingerprint());
            }
            resolveTheHoldAfterFailedSigning(hold, blindSignatures);
            throw signingFailure;
        }
        signedAnything = true;
        if (redemption != null) {
            redemptionReceipt = redemption.requestFingerprint();
        }

        commitOrStrandTheHold(hold);
        return new PostSwapResponse(blindSignatures);
    }

    /**
     * Claims the declaration against the coupon's cap. A refusal, or a claim whose outcome is
     * unknown (the database reply was lost), releases the hold and gives back whatever may have
     * been recorded, then rethrows: nothing has been signed.
     */
    private void claimOrReleaseTheHold(Redemption redemption, SwapProofHold hold) throws CashuErrorException {
        try {
            redemption.cap().claim(redemption.declared(), redemption.terms(), redemption.requestFingerprint(),
                    hold.holdId());
        } catch (CashuErrorException | RuntimeException refusedOrUnknown) {
            redemption.cap().giveBack(redemption.requestFingerprint());
            hold.release();
            throw refusedOrUnknown;
        }
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
