package xyz.tcheeric.cashu.mint.proto.service;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@Service
public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        vault.store(proofEntity);
    }

    @Override
    public void invalidate(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        vault.invalidate(proofEntity.getSecret());
    }

    @Override
    public void archive(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        vault.archive(proofEntity.getSecret());
    }

    @Override
    public void storePending(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        vault.storePending(proofEntity);
    }

    @Override
    public ProofEntity retrieveProof(String secret) {
        return DBProofVault.retrieveProof(secret);
    }
}
