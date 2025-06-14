package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;

@AllArgsConstructor
public class UpdateProofStateTask<T extends Secret> implements Task<Boolean> {
    private final Mint mint;
    private final Proof<T> proof;

    public Boolean execute() throws CashuErrorException {
        String unblindedSignature = proof.getUnblindedSignature().toString();
        String secret = proof.getSecret().toString();
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret);

        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, unblindedSignature, Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault vault = new FSProofVault(proofConfiguration);
        vault.storePending();
        return Boolean.TRUE;
    }
}
