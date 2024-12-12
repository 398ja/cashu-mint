package xyz.tcheeric.cashu.mint.proto.nut;

import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.common.model.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;
import lombok.NonNull;

public class NUT07 {

    public static final String UNSPENT = "UNSPENT";
    public static final String PENDING = "PENDING";
    public static final String SPENT = "SPENT";


    public static PostCheckStateResponse checkState(@NonNull PostCheckStateRequest postCheckStateRequest) {

        Mint mint = FSMintVault.load(false, true);
        MintConfiguration mintConfiguration = null;
        if (mint != null) {
            mintConfiguration = new MintConfiguration(mint.getId());
        }
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration);
        FSProofVault vault = new FSProofVault(proofConfiguration);
        PostCheckStateResponse response = new PostCheckStateResponse();

        postCheckStateRequest.getHashToCurveSecrets().forEach(hashToCurveSecret -> {
            try {
                String unblindedSignature = vault.retrieve(hashToCurveSecret.toString(), false);
                PostCheckStateResponse.ResponseSatate responseState = new PostCheckStateResponse.ResponseSatate();
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
