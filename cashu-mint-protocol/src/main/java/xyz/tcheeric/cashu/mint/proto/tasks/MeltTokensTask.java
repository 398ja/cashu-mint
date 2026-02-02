package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.util.UUID;

/**
 * Task responsible for loading a mint by id and executing the melting of tokens.
 */
public class MeltTokensTask<T extends Secret> extends InstrumentedTask<PostMeltResponse> {

    private final UUID mintId;
    private final PostMeltRequest<T> request;
    private final PaymentMethod method;
    private final MintProtocolService mintProtocolService;
    private final MintLoadService mintLoadService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;
    private final String unit;

    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          String unit,
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
        this.unit = unit;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintVaultService mintVaultService,
                          @NonNull ProofVaultService proofVaultService) {
        this(mintId, request, method, null, mintProtocolService, mintLoadService, mintVaultService, proofVaultService);
    }

    @Override
    protected PostMeltResponse doExecute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, true);
        MeltTask<T> meltTask = new MeltTask<>(request, method, unit, mint,
                mintProtocolService, mintLoadService, mintVaultService, proofVaultService);
        return meltTask.execute();
    }
}
