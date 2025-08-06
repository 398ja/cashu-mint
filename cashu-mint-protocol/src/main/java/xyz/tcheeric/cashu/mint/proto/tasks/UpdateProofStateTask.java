package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.RequiredArgsConstructor;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@RequiredArgsConstructor
public class UpdateProofStateTask<T extends Secret> implements Task<Boolean> {
    private final Mint mint;
    private final Proof<T> proof;

    public Boolean execute() throws CashuErrorException {
        ProofEntity proofEntity = MintProtocolUtil.toProofEntity(proof, MintProtocolUtil.toMintEntity(mint));
        DBProofVault vault = new DBProofVault(proofEntity);
        vault.storePending();
        return Boolean.TRUE;
    }
}
