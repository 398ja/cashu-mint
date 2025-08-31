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
public class ArchiveProofTask<T extends Secret> implements Task<Proof<T>> {

    private final Mint mint;
    private final Proof<T> proof;

    @Override
    public Proof<T> execute() throws CashuErrorException {
        ProofEntity proofEntity = MintProtocolUtil.toProofEntity(proof, MintProtocolUtil.toMintEntity(mint));
        DBProofVault proofVault = new DBProofVault();
        proofVault.archive(proofEntity.getSecret());
        return proof;
    }
}
