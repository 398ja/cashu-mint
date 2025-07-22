package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.RequiredArgsConstructor;
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

@RequiredArgsConstructor
public class ArchiveProofTask<T extends Secret> implements Task<Proof<T>> {

    private final Mint mint;
    private final Proof<T> proof;

    @Override
    public Proof<T> execute() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        String unblindedSignature = proof.getUnblindedSignature().toString();
        String secret = proof.getSecret().toString();
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret);
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, unblindedSignature, Utils.bytesToHexString(hashToCurveSecret));
        DBProofVault proofVault = new DBProofVault(proofConfiguration);
        proofVault.archive();
        return proof;
    }
}
