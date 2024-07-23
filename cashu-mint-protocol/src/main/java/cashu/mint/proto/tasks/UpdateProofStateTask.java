package cashu.mint.proto.tasks;

import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.util.CashuErrorException;
import cashu.common.util.Task;
import cashu.crypto.BDHKEUtils;
import cashu.util.Utils;
import cashu.vault.config.MintConfiguration;
import cashu.vault.config.ProofConfiguration;
import cashu.vault.impl.fs.FSProofVault;
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
