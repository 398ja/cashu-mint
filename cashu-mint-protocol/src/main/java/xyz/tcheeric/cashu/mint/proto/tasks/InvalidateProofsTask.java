package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;

import java.util.List;

@AllArgsConstructor
@Slf4j
public class InvalidateProofsTask<T extends Secret> implements Task<List<Proof<T>>> {

    private final Mint mint;
    private final List<Proof<T>> proofs;

    @Override
    public List<Proof<T>> execute() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        proofs
                .forEach(proof -> {
                    String unblindedSignature = proof.getUnblindedSignature().toString();
                    String secret = proof.getSecret().toString();
                    byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret);
                    ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, unblindedSignature, Utils.bytesToHexString(hashToCurveSecret));
                    DBProofVault proofVault = new DBProofVault(proofConfiguration);
                    proofVault.store();
                    try {
                        proofVault.invalidate();
                    } catch (CashuErrorException e) {
                        throw new RuntimeException(e);
                    }
                });

        return proofs;
    }
}
