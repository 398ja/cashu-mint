package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity entity) throws CashuErrorException {
        DBProofVault proofVault = new DBProofVault(entity);
        proofVault.store();
    }

    @Override
    public void invalidate(ProofEntity entity) throws CashuErrorException {
        DBProofVault proofVault = new DBProofVault(entity);
        proofVault.invalidate();
    }
}
