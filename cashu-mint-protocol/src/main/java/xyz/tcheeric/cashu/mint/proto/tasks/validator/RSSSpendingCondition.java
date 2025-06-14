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
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;

@AllArgsConstructor
public class RSSSpendingCondition implements SpendingCondition<RandomStringSecret> {

    @Setter(AccessLevel.NONE)
    private final Mint mint;

    @Override
    public void verify(Proof<RandomStringSecret> proof) throws CashuErrorException {

        // Check if proof has been used already
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        Secret secret = proof.getSecret();
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret.toString());
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault proofVault = new FSProofVault(proofConfiguration);
        var usedProof = proofVault.retrieve(proof.getSecret().toString(), false);
        if (usedProof != null) {
            throw new CashuErrorException("verify_proof_already_used_error");
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
                throw new CashuErrorException("verify_proof_key_set_not_found:" + proof.getKeySetId());
            }
        } else {
            throw new CashuErrorException("verify_proof_key_set_id_error");
        }

        // Verify the proof
        PrivateKey privateKey = getPrivateKey(proof, mint);
        if (privateKey == null) {
            throw new IllegalStateException("Private key not found");
        }

        byte[] C = proof.getUnblindedSignature().toBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            throw new CashuErrorException("verify_proof_failed_error");
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof, @NonNull Mint mint) {
        return MintProtocolUtil.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

}
