package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindedMessage;
import cashu.common.model.KeySet;
import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.Ability;
import cashu.common.protocol.CashuException;
import cashu.crypto.BDHKEUtils;
import cashu.util.ThreadUtil;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.InvalidObjectException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

@Nut(3)
@AllArgsConstructor
public class VerifyProofs implements Ability<Boolean> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Boolean apply() {
        try {
            ThreadUtil.builder().blocking(true).task(new VerifyProofsTask(mint, request)).build().run();
        } catch (TimeoutException e) {
            return false;
        }
        return true;
    }

    @Log
    @AllArgsConstructor
    static class VerifyProofsTask implements ThreadUtil.Task<Boolean> {

        private final Mint mint;
        private final PostSwapRequest request;

        @Override
        public Boolean execute() {
            try {
                validateAmounts();
                verifyProofs(request.getProofs(), mint);
            } catch (RuntimeException | InvalidObjectException e) {
                log.log(Level.WARNING, "Failed to verify proofs", e);
                return false;
            }

            return true;
        }

        private void validateAmounts() throws InvalidObjectException {
            var proofs = request.getProofs();
            var blindedMessages = request.getBlindedMessages();

            int proofsAmount = proofs.stream().mapToInt(Proof::getAmount).sum();
            int blindedMessagesAmount = blindedMessages.stream().mapToInt(BlindedMessage::getAmount).sum();

            if (proofsAmount != blindedMessagesAmount) {
                throw new InvalidObjectException("Proofs amount does not match blinded messages amount");
            }
        }

        private void verifyProofs(@NonNull List<Proof> proofs, @NonNull Mint mint) {
            for (Proof proof : proofs) {
                // Check if proof has been used already
                MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
                ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                FSProofVault proofVault = new FSProofVault(proofConfiguration);
                try {
                    var usedProof = proofVault.retrieve(proof.getSecret().toString(), false);
                    if (usedProof != null) {
                        throw new RuntimeException("Proof has already been used");
                    }
                } catch (CashuException e) {
                    throw new RuntimeException(e);
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
                        throw new RuntimeException("No key set found with ID: " + proof.getKeySetId());
                    }
                } else {
                    throw new IllegalStateException("Proof does not have a key set ID");
                }

                var C = proof.getUnblindedSignature().getBytes();
                var secret = proof.getSecret();
                if (!BDHKEUtils.verify(secret.toString(), mint.getPrivateKey().getBytes(), C)) {
                    throw new IllegalStateException("Verification failed. The secret and the un-blinded key do not match.");
                }
            }
        }

/*
        private void verifyProofs(@NonNull List<Proof> proofs, @NonNull Mint mint) {
            proofs
                    .forEach(proof -> {
                        // Check if proof has been used already
                        MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
                        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                        FSProofVault proofVault = new FSProofVault(proofConfiguration);
                        try {
                            var usedProof = proofVault.retrieve(proof.getSecret().toString(), false);
                            if (usedProof != null) {
                                log.log(Level.WARNING, "Proof has already been used");
                                throw new RuntimeException("Proof has already been used");
                            }
                        } catch (CashuException e) {
                            throw new RuntimeException(e);
                        }

                        // Check if proof id is valid
                        if (proof.getKeySetId() != null) {
                            mint.getKeySets().stream()
                                    .filter(ks -> proof.getKeySetId().equals(ks.getId()))
                                    .findFirst()
                                    .orElseThrow(() -> new RuntimeException("No key set found with ID: " + proof.getKeySetId()));
                        }

                        var C = proof.getUnblindedSignature().getBytes();
                        var secret = proof.getSecret();
                        if (!BDHKEUtils.verify(secret.toString(), mint.getPrivateKey().getBytes(), C)) {
                            throw new IllegalStateException("Verification failed. The secret and the un-blinded key do not match.");
                        }
                    });
        }
*/

    }

}
