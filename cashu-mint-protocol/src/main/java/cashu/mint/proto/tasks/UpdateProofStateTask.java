package cashu.mint.proto.tasks;

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

@AllArgsConstructor
public class UpdateProofStateTask implements BaseAbility.Task<Boolean> {
    private final Mint mint;
    private final Proof proof;

    public Boolean execute() {
        try {
            String unblindedSignature = proof.getUnblindedSignature().toString();
            String secret = proof.getSecret().toString();
            byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(secret);

            MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
            ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, unblindedSignature, Utils.bytesToHexString(hashToCurveSecret));
            FSProofVault vault = new FSProofVault(proofConfiguration);
            vault.storePending();
        } catch (CashuErrorException e) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
}
