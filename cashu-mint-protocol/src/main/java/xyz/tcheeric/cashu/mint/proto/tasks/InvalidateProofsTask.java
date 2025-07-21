package xyz.tcheeric.cashu.mint.proto.tasks;

import cashu.util.Utils;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@AllArgsConstructor
@Slf4j
public class InvalidateProofsTask<T extends Secret> implements Task<Boolean> {

    private final Mint mint;
    private final List<Proof<T>> proofs;

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
                    log.info("Invalidating proof {}", proof);
                    try {
                        proofVault.deletePending();
                        proofVault.store();
                    } catch (CashuErrorException e) {
                        throw new RuntimeException(e);
                    }
                });

        if (error.get() != null) {
            throw error.get();
        }

        return Boolean.TRUE;
    }
}
