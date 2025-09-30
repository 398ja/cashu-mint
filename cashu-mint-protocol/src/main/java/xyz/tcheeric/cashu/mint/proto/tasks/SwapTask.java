package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task executing a swap of proofs for new blinded signatures.
 */
@Slf4j
public class SwapTask<T extends Secret> implements Task<PostSwapResponse> {

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
    public PostSwapResponse execute() throws CashuErrorException {
        log.debug("Executing SwapTask for mint {}", mintId);
        Mint mint = mintLoadService.load(mintId, false);
        if (mint == null) {
            log.error("Mint not found");
            ErrorResponse error = new ErrorResponse("swap_mint_not_found");
            throw new CashuErrorException(error.toJson());
        }

        MintProtocolService service = MintProtocolServiceFactory.getInstance();

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
}
