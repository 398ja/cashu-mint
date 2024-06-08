package cashu.mint.actor.abilities.tasks;

import cashu.common.model.Mint;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

@AllArgsConstructor
@Log
public class InvalidateProofsTask implements BaseAbility.Task<Void> {

    private final Mint mint;
    private final PostSwapRequest request;

    @Override
    public Void execute() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getPrivateKey().toString());
        AtomicBoolean errorFlag = new AtomicBoolean(false);
        AtomicReference<CashuErrorException> error = new AtomicReference<>();
        request.getProofs()
                .forEach(proof -> {
                    ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, proof.getUnblindedSignature().toString(), proof.getSecret().toString());
                    FSProofVault proofVault = new FSProofVault(proofConfiguration);
                    // We invalidate the proof by storing it in the vault
                    log.log(Level.INFO, "Invalidating proof " + proof);
                    try {
                        proofVault.store();
                    } catch (CashuErrorException e) {
                        error.set(e);
                    }
                });

        if (error.get() != null) {
            throw error.get();
        }

        return null;
    }
}
