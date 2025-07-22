package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;

import java.util.UUID;

@Nut(7)
public class NUT07 {

    public static final String UNSPENT = "UNSPENT";
    public static final String PENDING = "PENDING";
    public static final String SPENT = "SPENT";


    public static PostCheckStateResponse checkState(@NonNull UUID mintId, @NonNull PostCheckStateRequest postCheckStateRequest) throws CashuErrorException {

        MintConfiguration mintConfiguration = new MintConfiguration(mintId.toString());
        DBMintVault.load(mintConfiguration, false, true);
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration);
        DBProofVault vault = new DBProofVault(proofConfiguration);

        PostCheckStateResponse response = new PostCheckStateResponse();

        postCheckStateRequest.getHashToCurveSecrets().forEach(hashToCurveSecret -> {
            try {
                String unblindedSignature = vault.retrieveSignature(hashToCurveSecret.toString(), false);
                PostCheckStateResponse.ResponseState responseState = new PostCheckStateResponse.ResponseState();
                if (unblindedSignature == null) {
                    unblindedSignature = vault.retrievePending(hashToCurveSecret.toString());
                    if (unblindedSignature == null) {
                        responseState.setState(UNSPENT);
                    } else {
                        responseState.setState(PENDING);
                    }
                } else {
                    responseState.setState(SPENT);
                }
                responseState.setHashToCurveSecret(hashToCurveSecret);
                String witness = vault.retrieveWitness(hashToCurveSecret.toString());
                responseState.setWitness(witness);
                response.addResponseState(responseState);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return response;
    }
}
