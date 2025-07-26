package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;

import java.util.UUID;

/**
 * Task that loads the mint and delegates to {@link MintTask} for signing.
 */
@Slf4j
public class MintTokensTask<T extends Secret> implements Task<PostMintResponse> {

    private final UUID mintId;
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final MintLoadService mintLoadService;
    private final MintProtocolService mintProtocolService;

    public MintTokensTask(@NonNull UUID mintId,
                          @NonNull PostMintRequest<T> postMintRequest,
                          @NonNull PaymentMethod method) {
        this(mintId, postMintRequest, method,
                new DefaultMintLoadService(),
                MintProtocolServiceFactory.getInstance());
    }

    public MintTokensTask(@NonNull UUID mintId,
                          @NonNull PostMintRequest<T> postMintRequest,
                          @NonNull PaymentMethod method,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintProtocolService mintProtocolService) {
        this.mintId = mintId;
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.mintLoadService = mintLoadService;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMintResponse execute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, false);
        return new MintTask<>(postMintRequest, method, mint, mintProtocolService).execute();
    }
}
