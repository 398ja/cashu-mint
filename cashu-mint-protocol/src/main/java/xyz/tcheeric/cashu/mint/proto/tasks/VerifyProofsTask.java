package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.PostSwapRequest;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.util.MintUtil;
import cashu.util.Utils;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.List;

@Log
@AllArgsConstructor
public class VerifyProofsTask implements Task<Void> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Void execute() throws CashuErrorException {
        validateAmounts();
        verifyProofs(request.getProofs(), mint);

        return null;
    }

    private void validateAmounts() throws CashuErrorException {
        var proofs = request.getProofs();
        var blindedMessages = request.getBlindedMessages();

        int proofsAmount = proofs.stream().mapToInt(Proof::getAmount).sum();
        int blindedMessagesAmount = blindedMessages.stream().mapToInt(BlindedMessage::getAmount).sum();

        if (proofsAmount != blindedMessagesAmount) {
            throw new CashuErrorException("validate_amounts_error");
        }
    }

    private void verifyProofs(@NonNull List<Proof> proofs, @NonNull Mint mint) throws CashuErrorException {
        for (Proof proof : proofs) {

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
                throw new RuntimeException("Private key not found");
            }

            byte[] C = proof.getUnblindedSignature().toBytes();
            if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
                throw new CashuErrorException("verify_proof_failed_error");
            }
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof proof, @NonNull Mint mint) {
        return MintUtil.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }
}
