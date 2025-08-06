package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

/**
 * Task that checks the state of a list of proofs for a given mint.
 */
public class CheckStateTask implements Task<PostCheckStateResponse> {

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
    public PostCheckStateResponse execute() throws CashuErrorException {
        PostCheckStateResponse response = new PostCheckStateResponse();

        MintEntity mintEntity = mintProtocolService.toMintEntity(new Mint(mintId.toString()));
        mintVaultService.load(mintEntity, false, true);

        for (var hash : request.getHashToCurveSecrets()) {
            try {
                ProofEntity proofEntity = proofVaultService.retrieveProof(hash.toString());
                PostCheckStateResponse.ResponseState state = new PostCheckStateResponse.ResponseState();
                if (ProofEntity.STATE_PENDING.equals(proofEntity.getState())) {
                    state.setState(NUT07.PENDING);
                } else {
                    state.setState(NUT07.SPENT);
                }
                state.setHashToCurveSecret(hash);
                state.setWitness(proofEntity.getWitness());
                response.addResponseState(state);
            } catch (CashuErrorException e) {
                if (e.getMessage() != null && e.getMessage().contains("not_found")) {
                    PostCheckStateResponse.ResponseState state = new PostCheckStateResponse.ResponseState();
                    state.setState(NUT07.UNSPENT);
                    state.setHashToCurveSecret(hash);
                    response.addResponseState(state);
                } else {
                    throw e;
                }
            }
        }

        return response;
    }
}
