package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.time.Duration;
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
    private final SignatureVaultService signatureVaultService;
    private final String unit;

    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          String unit,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintVaultService mintVaultService,
                          @NonNull ProofVaultService proofVaultService) {
        this(mintId, request, method, unit, mintProtocolService, mintLoadService,
                mintVaultService, proofVaultService, null);
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintVaultService mintVaultService,
                          @NonNull ProofVaultService proofVaultService) {
        this(mintId, request, method, null, mintProtocolService, mintLoadService,
                mintVaultService, proofVaultService, null);
    }

    /**
     * Spec 002 T215 constructor — threads {@link SignatureVaultService} so
     * the NUT-08 change return path in {@code MeltTask} can issue blind
     * signatures.
     */
    public MeltTokensTask(@NonNull UUID mintId,
                          @NonNull PostMeltRequest<T> request,
                          @NonNull PaymentMethod method,
                          String unit,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull MintLoadService mintLoadService,
                          @NonNull MintVaultService mintVaultService,
                          @NonNull ProofVaultService proofVaultService,
                          SignatureVaultService signatureVaultService) {
        this.mintId = mintId;
        this.request = request;
        this.method = method;
        this.mintProtocolService = mintProtocolService;
        this.mintLoadService = mintLoadService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
        this.signatureVaultService = signatureVaultService;
        this.unit = unit;
    }

    @Override
    protected PostMeltResponse doExecute() throws CashuErrorException {
        Mint mint = mintLoadService.load(mintId, true);
        // Spec 002 — pull the saga components from MintIntegrityContext if
        // they were installed at Spring bootstrap; otherwise MeltTask falls
        // back to its legacy single-transaction path.
        Duration timeout = MintIntegrityContext.meltPaymentTimeout();
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        MeltTask<T> meltTask = new MeltTask<>(request, method, unit, mint,
                mintProtocolService, mintLoadService, mintVaultService, proofVaultService,
                MintIntegrityContext.meltSagaRepository(),
                MintIntegrityContext.lightningPaymentPort(),
                timeout,
                signatureVaultService);
        return meltTask.execute();
    }
}
