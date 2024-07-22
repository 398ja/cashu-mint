package cashu.mint.proto.abilities.tasks;

import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.BDHKEUtils;
import cashu.util.Utils;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

@AllArgsConstructor
@Log
public class InvalidateProofsTask implements BaseAbility.Task<Boolean> {

    private final Mint mint;
    private final List<Proof> proofs;

    @Override
    public Boolean execute() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        AtomicReference<CashuErrorException> error = new AtomicReference<>();
        proofs
                .forEach(proof -> {
                    String unblindedSignature = proof.getUnblindedSignature().toString();
                    String secret = proof.getSecret().toString();
                    byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret);
                    ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, unblindedSignature, Utils.bytesToHexString(hashToCurveSecret));
                    FSProofVault proofVault = new FSProofVault(proofConfiguration);
                    // We invalidate the proof by storing it in the vault
                    log.log(Level.INFO, "Invalidating proof " + proof);
                    try {
                        proofVault.deletePending();
                        proofVault.store();
                    } catch (CashuErrorException e) {
                        error.set(e);
                    }
                });

        if (error.get() != null) {
            throw error.get();
        }

        return Boolean.TRUE;
    }
}
