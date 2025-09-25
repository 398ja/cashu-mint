package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@AllArgsConstructor
@Slf4j
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

        log.debug("Verify proof {}", proof);

        // Check if proof has been used already
        Secret secret = proof.getSecret();
        ProofEntity proofEntity = proofVaultService.retrieveProof(secret.toString());
        if (proofEntity != null) {
            log.error("verify_proof_already_used_error");
            ErrorResponse error = new ErrorResponse("verify_proof_already_used_error");
            throw new CashuErrorException(error.toJson());
        }

        // Check if keyset id is valid
        String proofKeySetId = proof.getKeySetId();
        if (proofKeySetId == null || proofKeySetId.isBlank()) {
            log.error("verify_proof_key_set_id_error");
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_id_error");
            throw new CashuErrorException(error.toJson());
        }

        boolean found = false;
        for (KeySet ks : mint.getKeySets()) {
            Object keySetId = ks.getId();
            if (keySetId == null) {
                continue;
            }
            if (proofKeySetId.equalsIgnoreCase(keySetId.toString())) {
                found = true;
                break;
            }
        }

        if (!found) {
            log.error("verify_proof_key_set_not_found");
            ErrorResponse error = new ErrorResponse("verify_proof_key_set_not_found");
            throw new CashuErrorException(error.toJson());
        }

        // Verify the proof
        PrivateKey privateKey = getPrivateKey(proof, mint);
        if (privateKey == null) {
            log.error("Private key not found");
            throw new IllegalStateException("Private key not found");
        }

        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            log.error("verify_proof_failed_error");
            ErrorResponse error = new ErrorResponse("verify_proof_failed_error");
            throw new CashuErrorException(error.toJson());
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof, @NonNull Mint mint) throws CashuErrorException {
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

}
