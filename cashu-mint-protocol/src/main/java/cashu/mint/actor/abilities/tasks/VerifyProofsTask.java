package cashu.mint.actor.abilities.tasks;

import cashu.common.model.BlindedMessage;
import cashu.common.model.KeySet;
import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.BDHKEUtils;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.InvalidObjectException;
import java.util.List;

@Log
@AllArgsConstructor
public class VerifyProofsTask implements BaseAbility.Task<Void> {

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
            MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
            ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
            FSProofVault proofVault = new FSProofVault(proofConfiguration);
            var usedProof = proofVault.retrieve(proof.getSecret().toString(), false);
            if (usedProof != null) {
                throw new CashuErrorException("verify_proof_already_used_error");
            }

            // Check if proof id is valid
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

            var C = proof.getUnblindedSignature().getBytes();
            var secret = proof.getSecret();
            if (!BDHKEUtils.verify(secret.toString(), mint.getPrivateKey().getBytes(), C)) {
                throw new CashuErrorException("verify_proof_failed_error");
            }
        }
    }
}
