package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;

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

    public SwapTask(@NonNull UUID mintId, @NonNull PostSwapRequest<T> request) {
        this(mintId, request, new DefaultMintLoadService());
    }

    public SwapTask(@NonNull UUID mintId,
                    @NonNull PostSwapRequest<T> request,
                    @NonNull MintLoadService mintLoadService) {
        this.mintId = mintId;
        this.request = request;
        this.mintLoadService = mintLoadService;
    }

    @Override
    public PostSwapResponse execute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, false);
        if (mint == null) {
            throw new CashuErrorException("swap_mint_not_found");
        }

        MintProtocolService service = MintProtocolServiceFactory.getInstance();

        new VerifyProofsTask<>(mint, request, service).execute();
        new InvalidateProofsTask<>(mint, request.getInputs()).execute();

        List<BlindSignature> blindSignatures = new ArrayList<>();
        for (BlindedMessage bm : request.getBlindedMessages()) {
            BlindSignature sig = new SignBlindedMessageTask(mint, bm, service).execute();
            blindSignatures.add(sig);
        }

        PostSwapResponse response = new PostSwapResponse(blindSignatures);
        new VerifyFeesTask<>(request, response, mintLoadService).execute();

        return response;
    }
}
