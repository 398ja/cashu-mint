package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@Service
@Slf4j
public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        ProofEntity persisted = vault.store(proofEntity);
        syncPersistentState(proofEntity, persisted);
    }

    @Override
    public void invalidate(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        ProofEntity persisted = ensurePersistedEntity(proofEntity);
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("Unable to resolve proof id for invalidation");
        }
        ProofEntity updated = vault.invalidate(persisted.getId().toString());
        syncPersistentState(proofEntity, updated);
    }

    @Override
    public void archive(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        ProofEntity persisted = ensurePersistedEntity(proofEntity);
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("Unable to resolve proof id for archiving");
        }
        ProofEntity updated = vault.archive(persisted.getId().toString());
        syncPersistentState(proofEntity, updated);
    }

    @Override
    public void storePending(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        ProofEntity persisted = vault.storePending(proofEntity);
        syncPersistentState(proofEntity, persisted);
    }

    @Override
    public ProofEntity retrieveProof(String secret) {
        return DBProofVault.retrieveProof(secret);
    }

    private ProofEntity ensurePersistedEntity(ProofEntity proofEntity) {
        if (proofEntity == null) {
            return null;
        }
        if (proofEntity.getId() != null) {
            return proofEntity;
        }
        try {
            if (proofEntity.getMint() != null && proofEntity.getMint().getId() != null && proofEntity.getSecret() != null) {
                ProofEntity persisted = DBProofVault.retrieveProof(proofEntity.getMint().getId().toString(), proofEntity.getSecret());
                syncPersistentState(proofEntity, persisted);
                return persisted;
            }
        } catch (Exception ex) {
            log.debug("Failed to reload proof entity by secret", ex);
        }
        return proofEntity;
    }

    private void syncPersistentState(ProofEntity target, ProofEntity persisted) {
        if (target == null || persisted == null) {
            return;
        }
        target.setId(persisted.getId());
        target.setState(persisted.getState());
        target.setUpdatedAt(persisted.getUpdatedAt());
        target.setVersion(persisted.getVersion());
    }
}
