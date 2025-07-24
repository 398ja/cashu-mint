package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

@Nut(value = 7, description = "Check the state of a proof")
public class NUT07 {

    public static final String UNSPENT = "UNSPENT";
    public static final String PENDING = "PENDING";
    public static final String SPENT = "SPENT";


    public static PostCheckStateResponse checkState(@NonNull UUID mintId, @NonNull PostCheckStateRequest postCheckStateRequest) throws CashuErrorException {

        PostCheckStateResponse response = new PostCheckStateResponse();

        MintEntity mintEntity = MintProtocolUtil.toMintEntity(new Mint(mintId.toString()));
        DBMintVault.load(mintEntity, false, true);

        postCheckStateRequest.getHashToCurveSecrets().forEach(hashToCurveSecret -> {
            try {
                ProofEntity proofEntity1 = DBProofVault.retrieveProof(hashToCurveSecret.toString()).getEntity();
                PostCheckStateResponse.ResponseState responseState = new PostCheckStateResponse.ResponseState();

                if (proofEntity1.getState().equals(ProofEntity.STATE_PENDING)) {
                    responseState.setState(UNSPENT);
                } else {
                    responseState.setState(SPENT);
                }

                responseState.setHashToCurveSecret(hashToCurveSecret);
                String witness = proofEntity1.getWitness();
                responseState.setWitness(witness);
                response.addResponseState(responseState);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return response;
    }
}
