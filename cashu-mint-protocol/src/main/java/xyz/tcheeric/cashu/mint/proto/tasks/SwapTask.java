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
        validateNoMixedProofTypes(request.getInputs());

        new VerifyProofsTask<>(mint, request, service).execute();

        List<BlindSignature> blindSignatures = new ArrayList<>();
        for (BlindedMessage bm : request.getBlindedMessages()) {
            BlindSignature sig = new SignBlindedMessageTask(mint, bm, service, signatureVaultService).execute();
            blindSignatures.add(sig);
        }

        PostSwapResponse response = new PostSwapResponse(blindSignatures);

        try {
            new VerifyFeesTask<>(request, response, mintLoadService).execute();
            new InvalidateProofsTask<>(mint, request.getInputs()).execute();
        } catch (CashuErrorException e) {
            throw e;
        }

        return response;
    }

    /**
     * Validates that the swap request does not mix voucher and regular proofs.
     * Mixing proof types in the same swap operation is not allowed.
     *
     * @param proofs the list of proofs to validate
     * @throws CashuErrorException if mixed proof types are detected
     */
    private void validateNoMixedProofTypes(List<Proof<T>> proofs) throws CashuErrorException {
        if (proofs == null || proofs.isEmpty()) {
            return;
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
