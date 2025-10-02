package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.util.UUID;

/**
 * Task responsible for loading a mint by id and executing the melting of tokens.
 */
public class MeltTokensTask<T extends Secret> implements Task<PostMeltResponse> {

    private final UUID mintId;
    private final PostMeltRequest<T> request;
    private final PaymentMethod method;
    private final MintProtocolService mintProtocolService;
    private final MintLoadService mintLoadService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;

    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintVaultService mintVaultService,
                          @NonNull ProofVaultService proofVaultService) {
        this.mintId = mintId;
        this.request = request;
        this.method = method;
        this.mintProtocolService = mintProtocolService;
        this.mintLoadService = mintLoadService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
    }

    @Override
    public PostMeltResponse execute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, true);
        MeltTask<T> meltTask = new MeltTask<>(request, method, mint,
                mintProtocolService, mintLoadService, mintVaultService, proofVaultService);
        return meltTask.execute();
    }
}
