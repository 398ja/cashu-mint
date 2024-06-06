package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.Mint;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.Ability;
import cashu.common.protocol.CashuException;
import cashu.util.ThreadUtil;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

@Nut(3)
@Log
@AllArgsConstructor
public class InvalidateProofs implements Ability<Boolean> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Boolean apply() {
        try {
            ThreadUtil.builder().blocking(true).task(new InvalidateProofsTask(mint, request)).build().run();
        } catch (TimeoutException e) {
            log.log(Level.SEVERE, "Failed to invalidate proofs", e);
            return false;
        }
        return true;
    }

    @AllArgsConstructor
    static class InvalidateProofsTask implements ThreadUtil.Task<Boolean> {

        private final Mint mint;
        private final PostSwapRequest request;

        @Override
        public Boolean execute() {
            MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
            AtomicBoolean errorFlag = new AtomicBoolean(false);
            request.getProofs()
                    .forEach(proof -> {
                        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                        FSProofVault proofVault = new FSProofVault(proofConfiguration);
                        try {
                            // We invalidate the proof by storing it in the vault
                            log.log(Level.INFO, "Invalidating proof " + proof);
                            proofVault.store();
                        } catch (CashuException e) {
                            log.log(Level.SEVERE, "Failed to invalidate proof " + proof, e);
                            errorFlag.set(true);
                        }
                    });
            return !errorFlag.get();
        }
    }
}
