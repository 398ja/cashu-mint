package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@Service
@Slf4j
public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        log.debug("Persisting proof secret={} state={} (store)",
                maskSecret(proofEntity != null ? proofEntity.getSecret() : null),
                proofEntity != null ? proofEntity.getState() : null);
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
        log.debug("Marking proof id={} secret={} as spent", persisted.getId(), maskSecret(persisted.getSecret()));
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
        log.debug("Archiving proof id={} secret={}", persisted.getId(), maskSecret(persisted.getSecret()));
        ProofEntity updated = vault.archive(persisted.getId().toString());
        syncPersistentState(proofEntity, updated);
    }

    @Override
    public void storePending(ProofEntity proofEntity) {
        DBProofVault vault = new DBProofVault();
        log.debug("Persisting proof secret={} state=PENDING (storePending)",
                maskSecret(proofEntity != null ? proofEntity.getSecret() : null));
        ProofEntity persisted = vault.storePending(proofEntity);
        syncPersistentState(proofEntity, persisted);
    }

    @Override
    public ProofEntity retrieveProof(String secret) {
        try {
            return DBProofVault.retrieveProof(secret);
        } catch (HttpClientErrorException.NotFound notFound) {
            log.info("Vault reports secret {} is not present; treating as unspent", maskSecret(secret));
            return null;
        }
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
        } catch (HttpClientErrorException.NotFound ignored) {
            log.info("Vault has no record for mintId={} secret {}; continuing with transient entity", 
                    proofEntity.getMint() != null ? proofEntity.getMint().getId() : null,
                    maskSecret(proofEntity.getSecret()));
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
        log.debug("Updated local proof state id={} state={} version={}",
                persisted.getId(), persisted.getState(), persisted.getVersion());
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.length() < 8) {
            return secret;
        }
        return secret.substring(0, 6) + "…" + secret.substring(secret.length() - 4);
    }
}
