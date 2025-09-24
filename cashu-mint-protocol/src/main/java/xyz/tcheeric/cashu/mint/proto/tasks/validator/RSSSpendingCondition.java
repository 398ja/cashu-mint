package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@AllArgsConstructor
public class RSSSpendingCondition implements SpendingCondition<RandomStringSecret> {

    @Setter(AccessLevel.NONE)
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;

    public RSSSpendingCondition(@NonNull Mint mint,
                                @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    @Override
    public void verify(Proof<RandomStringSecret> proof) throws CashuErrorException {

        // Check if proof has been used already
        Secret secret = proof.getSecret();
        ProofEntity proofEntity = proofVaultService.retrieveProof(secret.toString());
        if (proofEntity != null) {
            ErrorResponse error = new ErrorResponse("verify_proof_already_used_error");
            throw new CashuErrorException(error.toJson());
        }

        // Check if keyset id is valid
        if (proof.getKeySetId() != null) {
            boolean found = false;
            for (KeySet ks : mint.getKeySets()) {
                if (proof.getKeySetId().equals(ks.getId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                ErrorResponse error = new ErrorResponse("verify_proof_key_set_not_found");
                throw new CashuErrorException(error.toJson());
            }
        } else {
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_id_error");
            throw new CashuErrorException(error.toJson());
        }

        // Verify the proof
        PrivateKey privateKey = getPrivateKey(proof, mint);
        if (privateKey == null) {
            throw new IllegalStateException("Private key not found");
        }

        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            ErrorResponse error = new ErrorResponse("verify_proof_failed_error");
            throw new CashuErrorException(error.toJson());
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof, @NonNull Mint mint) throws CashuErrorException {
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

}
