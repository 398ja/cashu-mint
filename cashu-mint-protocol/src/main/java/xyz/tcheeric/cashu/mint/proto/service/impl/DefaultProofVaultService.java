package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

@Service
@Slf4j
public class DefaultProofVaultService implements ProofVaultService {
    @Override
    public void store(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault();
        vault.store(proofEntity);
    }

    @Override
    public void invalidate(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault();
        vault.invalidate(proofEntity.getId().toString());
    }

    @Override
    public void archive(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault();
        vault.archive(proofEntity.getId().toString());
    }

    @Override
    public void storePending(ProofEntity proofEntity) throws CashuErrorException {
        DBProofVault vault = new DBProofVault();
        vault.storePending(proofEntity);
    }

    @Override
    public ProofEntity retrieveProof(String secret) throws CashuErrorException {
        try {
            Secret normalizedSecret = SecretUtil.toSecret(secret);
            return DBProofVault.retrieveProof(SecretUtil.toY(normalizedSecret));
        } catch (CashuErrorException e) {
            // Log and return null so callers can treat missing/errored lookups as no-proof-found
            log.warn("DefaultProofVaultService: failed to retrieve proof for secret {}: {}", secret, e.getMessage());
            return null;
        }
    }
}
