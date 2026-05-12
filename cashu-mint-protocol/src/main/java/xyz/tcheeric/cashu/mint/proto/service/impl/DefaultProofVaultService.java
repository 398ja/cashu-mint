package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
            // Use toYFromString to compute Y directly from the secret string.
            // Per Cashu spec, Y = hash_to_curve(secret_string) where secret_string
            // is the UTF-8 encoding of the secret (e.g., "64hexchars" or "["VOUCHER",...]")
            String normalizedSecret = SecretUtil.toYFromString(secret);
            return DBProofVault.retrieveProof(normalizedSecret);
        } catch (CashuErrorException e) {
            // Log and return null so callers can treat missing/errored lookups as no-proof-found
            log.warn("DefaultProofVaultService: failed to retrieve proof for secret {}: {}", secret, e.getMessage());
            return null;
        }
    }

    @Override
    public ProofEntity retrieveProofByY(String yHex) throws CashuErrorException {
        try {
            // NUT-07 receives the hash-to-curve point Y directly from the client.
            // Passing it through retrieveProof() would re-run hash_to_curve and
            // silently miss every lookup, so DBProofVault is queried directly.
            return DBProofVault.retrieveProof(yHex);
        } catch (CashuErrorException e) {
            log.warn("DefaultProofVaultService: failed to retrieve proof by Y {}: {}", yHex, e.getMessage());
            return null;
        }
    }
}
