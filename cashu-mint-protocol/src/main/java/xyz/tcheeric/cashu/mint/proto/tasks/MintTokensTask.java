package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;

import java.util.UUID;

/**
 * Task that loads the mint and delegates to {@link MintTask} for signing.
 */
@Slf4j
public class MintTokensTask<T extends Secret> extends InstrumentedTask<PostMintResponse> {

    private final UUID mintId;
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final String unit;
    private final MintLoadService mintLoadService;
    private final MintProtocolService mintProtocolService;
    private final SignatureVaultService signatureVaultService;

    public MintTokensTask(@NonNull UUID mintId,
                          @NonNull PostMintRequest<T> postMintRequest,
                          @NonNull PaymentMethod method,
                          @NonNull SignatureVaultService signatureVaultService) {
        this(mintId, postMintRequest, method, null,
                new DefaultMintLoadService(),
                MintProtocolServiceFactory.getInstance(),
                signatureVaultService);
    }

    public MintTokensTask(@NonNull UUID mintId,
                          @NonNull PostMintRequest<T> postMintRequest,
                          @NonNull PaymentMethod method,
                          String unit,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull SignatureVaultService signatureVaultService) {
        this.mintId = mintId;
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.unit = unit;
        this.mintLoadService = mintLoadService;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MintTokensTask(@NonNull UUID mintId,
                          @NonNull PostMintRequest<T> postMintRequest,
                          @NonNull PaymentMethod method,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull SignatureVaultService signatureVaultService) {
        this(mintId, postMintRequest, method, null, mintLoadService, mintProtocolService, signatureVaultService);
    }

    @Override
    protected PostMintResponse doExecute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, false);
        return new MintTask<>(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService).execute();
    }
}
