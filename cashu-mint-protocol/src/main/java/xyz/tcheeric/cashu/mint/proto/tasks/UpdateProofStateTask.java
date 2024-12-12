package xyz.tcheeric.cashu.mint.proto.tasks;

import cashu.util.Utils;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class UpdateProofStateTask implements Task<Boolean> {
    private final Mint mint;
    private final Proof proof;

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
