package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;

// TEST -
@Slf4j
public class MeltTask<T extends Secret> extends InstrumentedTask<PostMeltResponse> {
    private final PostMeltRequest<T> postMeltRequest;
    private final PaymentMethod method;
    private final String unit;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final MintLoadService mintLoadService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;

    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService) {
        this(postMeltRequest, method, null, mint, mintProtocolService, new DefaultMintLoadService(), new DefaultMintVaultService(), new DefaultProofVaultService());
    }

    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, String unit, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService) {
        this.postMeltRequest = postMeltRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintLoadService = mintLoadService;
        this.mintProtocolService = mintProtocolService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService) {
        this(postMeltRequest, method, null, mint, mintProtocolService, mintLoadService, mintVaultService, proofVaultService);
    }

    @Override
    protected PostMeltResponse doExecute() throws CashuErrorException {
        // TODO - Use java module instead?
        List<Proof<T>> proofsToMelt = postMeltRequest.getInputs();
        try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(
                proofsToMelt.stream().map(proof -> proof.getSecret().toString()).toList())) {
            for (Proof<T> proof : proofsToMelt) {
                // Model B enforcement: Reject voucher secrets in melt operations
                if (isVoucherSecret(proof.getSecret())) {
                    log.warn("Voucher secret rejected in melt operation (Model B enforcement)");
                    ErrorResponse error = new ErrorResponse(
                        "voucher_not_accepted",
                        "Vouchers cannot be melted at mint (Model B). " +
                        "Please redeem with issuing merchant."
                    );
                    throw new CashuErrorException(error.toJson());
                }

                if (!verify(proof)) {
                    ErrorResponse error = new ErrorResponse("melt_proof_verification_error");
                    throw new CashuErrorException(error.toJson());
                }
            }

            var keySetId = proofsToMelt.get(0).getKeySetId();
            var keyset = mintLoadService.keySet(keySetId);
            var quoteId = postMeltRequest.getQuoteId();
            var gateway = unit == null ? mintProtocolService.createGateway(method)
                    : mintProtocolService.createGateway(method, unit);
            long invoiceAmount = gateway.getAmount(quoteId);
            var request = gateway.getRequest(quoteId);
            ExactFeeReserveResolver.Resolved feeReserve =
                    ExactFeeReserveResolver.resolve(gateway, quoteId, postMeltRequest, keyset);
            long proofSum = proofsToMelt.stream()
                    .mapToLong(Proof::getAmount)
                    .sum();
            if (log.isDebugEnabled()) {
                log.debug("Processing melt quote {} for request {} with invoice={} lightningReserve={} inputFees={} proofSum={}",
                        quoteId, request, invoiceAmount,
                        feeReserve.getLightningReserve(), feeReserve.getInputFees(), proofSum);
            }

            // Spec 002 FR-001 / FR-009: sum(proofs) >= invoice + exactFeeReserve, long arithmetic.
            // The validator throws insufficient_input *before* any external payment is attempted (SC-001).
            BurnAmountValidator.requireFunded(proofSum, invoiceAmount, feeReserve.getTotal());

            gateway.pay(quoteId);
            if (!gateway.checkPaymentStatus(quoteId)) {
                ErrorResponse error = new ErrorResponse("melt_invoice_not_paid_error");
                throw new CashuErrorException(error.toJson());
            }

            try {
                persistPendingProofs(proofsToMelt);
            } catch (CashuErrorException | RuntimeException e) {
                log.error("Failed to mark proofs as pending for melt quote {}", quoteId, e);
                ErrorResponse error = new ErrorResponse("melt_proof_pending_error");
                throw new CashuErrorException(error.toJson());
            }

            // Invalidate the proofsToMelt.
            createInvalidateProofsTask(proofsToMelt).execute();

            return new PostMeltResponse(true, gateway.getPaymentPreimage(quoteId));
        }
    }

    public boolean verify(@NonNull Proof proof) throws CashuErrorException {
        if (proof.getSecret() == null || proof.getUnblindedSignature() == null) {
            log.error("melt_verify_missing_data secretPresent={} signaturePresent={}",
                    proof.getSecret() != null, proof.getUnblindedSignature() != null);
            return false;
        }
        PrivateKey privateKey = mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
        if (privateKey != null) {
            try {
                return BDHKEUtils.verify(proof.getSecret().toString(), privateKey.toBytes(),
                        proof.getUnblindedSignature().getBytes());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.error("melt_verify_crypto_error", e);
                ErrorResponse error = new ErrorResponse("melt_proof_verification_error");
                throw new CashuErrorException(error.toJson());
            }
        }
        return false;
    }

    private void persistPendingProofs(List<Proof<T>> proofsToMelt) throws CashuErrorException {
        MintEntity mintEntity = mintVaultService.retrieveMint(mint.getId());
        for (Proof<T> proof : proofsToMelt) {
            ProofEntity proofEntity = new ProofEntity();
            proofEntity.setAmount(proof.getAmount());
            proofEntity.setSecret(proof.getSecret().toString());
            if (proof.getWitness() != null) {
                proofEntity.setWitness(proof.getWitness().toString());
            }
            proofEntity.setUnblindedSignature(proof.getUnblindedSignature().toString());
            proofEntity.setMint(mintEntity);
            proofEntity.setState(ProofEntity.STATE_PENDING);
            proofVaultService.storePending(proofEntity);
        }
    }

    protected InvalidateProofsTask<T> createInvalidateProofsTask(List<Proof<T>> proofs) {
        return new InvalidateProofsTask<>(mint, proofs, mintVaultService, proofVaultService);
    }

    /**
     * Checks if a secret is a VoucherSecret (Model B enforcement).
     */
    private boolean isVoucherSecret(Secret secret) {
        return VoucherSecretDetector.isVoucherSecret(secret);
    }
}
