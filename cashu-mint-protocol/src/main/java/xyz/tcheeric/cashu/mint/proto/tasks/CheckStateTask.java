package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

/**
 * Task that checks the state of a list of proofs for a given mint.
 */
public class CheckStateTask extends InstrumentedTask<PostCheckStateResponse> {

    private final UUID mintId;
    private final PostCheckStateRequest request;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;
    private final MintVaultService mintVaultService;

    public CheckStateTask(@NonNull UUID mintId,
                          @NonNull PostCheckStateRequest request) {
        this(mintId,
                request,
                MintProtocolServiceFactory.getInstance(),
                new DefaultProofVaultService(),
                new DefaultMintVaultService());
    }

    public CheckStateTask(@NonNull UUID mintId,
                          @NonNull PostCheckStateRequest request,
                          @NonNull MintProtocolService mintProtocolService,
                          @NonNull ProofVaultService proofVaultService,
                          @NonNull MintVaultService mintVaultService) {
        this.mintId = mintId;
        this.request = request;
        this.mintProtocolService = mintProtocolService;
        this.proofVaultService = proofVaultService;
        this.mintVaultService = mintVaultService;
    }

    @Override
    protected PostCheckStateResponse doExecute() throws CashuErrorException {
        requireCountWithinLimit();
        PostCheckStateResponse response = new PostCheckStateResponse();

        MintEntity mintEntity = mintProtocolService.toMintEntity(new Mint(mintId.toString()));
        mintVaultService.load(mintEntity, false, true);

        for (HashToCurveSecret hash : request.getHashToCurveSecrets()) {
            PostCheckStateResponse.ResponseState state = new PostCheckStateResponse.ResponseState();
            state.setHashToCurveSecret(hash);
            // NUT-07 supplies Y itself, which is the storage key: nothing is hashed.
            ProofEntity proofEntity = proofVaultService.retrieveProof(StorageKey.of(hash));
            if (proofEntity == null) {
                state.setState(NUT07.UNSPENT);
            } else {
                if (ProofEntity.STATE_PENDING.equals(proofEntity.getState())) {
                    state.setState(NUT07.PENDING);
                } else {
                    state.setState(NUT07.SPENT);
                }
                state.setWitness(proofEntity.getWitness());
            }
            response.addResponseState(state);
        }

        return response;
    }

    /**
     * Refuses a request carrying more {@code Ys} than {@link PostCheckStateRequest#MAX_SECRETS}.
     *
     * <p>The limit is declared on the request DTO as a Bean Validation {@code @Size} constraint,
     * which only fires where something applies it. Enforced here because this task performs one
     * vault lookup per requested {@code Y} and is run once per mint by
     * {@code CrossMintCheckStateMerger} — across active and archived mints alike — so an
     * unbounded list is multiplied before it reaches the vault that every value-moving path
     * depends on. {@code /v1/checkstate} carries no authenticated principal, so the bound cannot
     * rest on the caller being known.
     *
     * @throws CashuErrorException {@link CashuErrorCode#too_many_inputs} when the limit is exceeded
     */
    private void requireCountWithinLimit() throws CashuErrorException {
        int requestedCount = request.getHashToCurveSecrets().size();
        if (requestedCount > PostCheckStateRequest.MAX_SECRETS) {
            throw new CashuErrorException(CashuErrorCode.too_many_inputs,
                    "Maximum " + PostCheckStateRequest.MAX_SECRETS + " secrets allowed");
        }
    }
}
