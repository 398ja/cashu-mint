package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.error.CashuException;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Proof;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.Ability;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import lombok.AllArgsConstructor;

import java.io.InvalidObjectException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Nut(3)
@AllArgsConstructor
public class VerifyProof implements Ability<Void> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Void apply() throws CashuException {
        try {
            validateAmounts();
            verifyProofs(request.getProofs(), mint);
        } catch (RuntimeException | InvalidObjectException e) {
            throw new CashuException(e);
        }

        return null;
    }

    private void validateAmounts() throws InvalidObjectException {
        var proofs = request.getProofs();
        var blindedMessages = request.getBlindedMessages();

        int proofsAmount = proofs.stream().mapToInt(Proof::getAmount).sum();
        int blindedMessagesAmount = blindedMessages.stream().mapToInt(BlindedMessage::getAmount).sum();

        if (proofsAmount != blindedMessagesAmount) {
            throw new InvalidObjectException("Proofs and blinded messages amounts do not match");
        }
    }

    private void verifyProofs(List<Proof> proofs, Mint mint) {
        proofs.stream().forEach(proof -> {
            // TODO - Check if proof has already been used

            // Check if proof id is valid
            if(proof.getKeySetId() != null) {
                mint.getKeySets().stream().filter(ks -> proof.getKeySetId().equals(ks.getId())).findFirst().orElseThrow();
            }

            var C = proof.getUnblindedSignature().getBytes();
            var secret = proof.getSecret();
            try {
                if (!BDHKEUtils.verify(secret.toString(), mint.getPrivateKey().getBytes(), C)) {
                    throw new RuntimeException("Verification failed. The secret and the unblinded key do not match.");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
