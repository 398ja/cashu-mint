package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Proof;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.Ability;
import cashu.common.protocol.CashuException;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import cashu.util.ThreadUtil;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.io.InvalidObjectException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Nut(3)
@AllArgsConstructor
public class VerifyProofs implements Ability<Void> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Void apply() {
        try {
            ThreadUtil.builder().blocking(true).task(new VerifyProofsTask(mint, request)).build().run();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @AllArgsConstructor
    static class VerifyProofsTask implements ThreadUtil.Task<Void> {

        private final Mint mint;
        private final PostSwapRequest request;

        @Override
        public Void execute() {
            try {
                validateAmounts();
                verifyProofs(request.getProofs(), mint);
            } catch (RuntimeException | InvalidObjectException e) {
                throw new RuntimeException(e);
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

        private void verifyProofs(@NonNull List<Proof> proofs, @NonNull Mint mint) {
            proofs.stream()
                    .forEach(proof -> {
                        // Check if proof has been used already
                        MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
                        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                        FSProofVault proofVault = new FSProofVault(proofConfiguration);
                        try {
                            var usedProof = proofVault.retrieve(proof.getSecret().toString());
                            if (usedProof != null) {
                                throw new RuntimeException("Proof has already been used");
                            }
                        } catch (CashuException e) {
                            throw new RuntimeException(e);
                        }

                        // Check if proof id is valid
                        if (proof.getKeySetId() != null) {
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

}
