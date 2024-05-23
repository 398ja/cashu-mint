package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.Ability;
import cashu.common.protocol.CashuException;
import cashu.mint.actor.Mint;
import cashu.util.ThreadUtil;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;

import java.util.concurrent.TimeoutException;

@Nut(3)
@AllArgsConstructor
public class InvalidateProofs implements Ability<Void> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Void apply() {
        try {
            ThreadUtil.builder().blocking(true).task(new InvalidateProofsTask(mint, request)).build().run();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @AllArgsConstructor
    static class InvalidateProofsTask implements ThreadUtil.Task<Void> {

        private final Mint mint;
        private final PostSwapRequest request;

        @Override
        public Void execute() {
            MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
            request.getProofs().stream()
                    .forEach(proof -> {
                        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                        FSProofVault proofVault = new FSProofVault(proofConfiguration);
                        try {
                            proofVault.store();
                        } catch (CashuException e) {
                            throw new RuntimeException(e);
                        }
                    });
            return null;
        }
    }
}
