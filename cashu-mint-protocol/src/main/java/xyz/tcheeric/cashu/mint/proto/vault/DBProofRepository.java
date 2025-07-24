package xyz.tcheeric.cashu.mint.proto.vault;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public class DBProofRepository implements ProofRepository {
    @Override
    public ProofEntity retrieveProof(String secret) throws CashuErrorException {
        return DBProofVault.retrieveProof(secret).getEntity();
    }

    @Override
    public void store(ProofEntity entity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(entity);
        vault.store();
    }

    @Override
    public void storePending(ProofEntity entity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(entity);
        vault.storePending();
    }

    @Override
    public void invalidate(ProofEntity entity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(entity);
        vault.invalidate();
    }

    @Override
    public void archive(ProofEntity entity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(entity);
        vault.archive();
    }
}
