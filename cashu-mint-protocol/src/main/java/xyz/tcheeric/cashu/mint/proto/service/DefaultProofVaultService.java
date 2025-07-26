package xyz.tcheeric.cashu.mint.proto.service;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@Service
public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(proofEntity);
        vault.store();
    }

    @Override
    public void invalidate(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(proofEntity);
        vault.invalidate();
    }

    @Override
    public void archive(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(proofEntity);
        vault.archive();
    }

    @Override
    public void storePending(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault(proofEntity);
        vault.storePending();
    }

    @Override
    public ProofEntity retrieveProof(String secret) throws CashuErrorException {
        return DBProofVault.retrieveProof(secret).getEntity();
    }
}
