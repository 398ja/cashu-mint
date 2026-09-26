package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

@Service
@Slf4j
public class DefaultProofVaultService implements ProofVaultService {

    @Override
    public ProofEntity retrieveProof(UUID mintId, ProofSecret secret) throws CashuErrorException {
        try {
            return firstStoredUnderAnyKey(mintId, secret);
        } catch (CashuErrorException e) {
            // Log and return null so callers can treat missing/errored lookups as no-proof-found
            log.warn("DefaultProofVaultService: failed to retrieve proof for secret {}: {}", secret, e.getMessage());
            return null;
        }
    }

    /**
     * Looks the proof up under every key it could have been recorded under, within this mint.
     *
     * <p>A proof spent before the NUT-00 secret encoding was corrected is recorded under the
     * legacy curve point. Checking only the spec point would report such a proof unspent and let
     * it be spent a second time, so both points are queried before concluding it is unspent.
     */
    private ProofEntity firstStoredUnderAnyKey(UUID mintId, ProofSecret secret) throws CashuErrorException {
        for (StorageKey key : SpentProofKey.lookupKeys(secret)) {
            ProofEntity stored = DBProofVault.retrieveProof(mintId.toString(), key.hex());
            if (stored != null) {
                return stored;
            }
        }
        return null;
    }

    /**
     * Records a spend under the key an existing record already uses, falling back to the spec key
     * when the proof has never been seen. This keeps one logical proof to one row across the
     * NUT-00 encoding migration.
     */
    @Override
    public StorageKey storageKeyFor(UUID mintId, ProofSecret secret) throws CashuErrorException {
        for (StorageKey key : SpentProofKey.lookupKeys(secret)) {
            if (DBProofVault.retrieveProof(mintId.toString(), key.hex()) != null) {
                return key;
            }
        }
        return SpentProofKey.issuanceKey(secret);
    }

    @Override
    public ProofEntity retrieveProof(StorageKey key) throws CashuErrorException {
        try {
            // The key is already the stored point, so the vault is queried with it as-is.
            return DBProofVault.retrieveProof(key.hex());
        } catch (CashuErrorException e) {
            log.warn("DefaultProofVaultService: failed to retrieve proof by Y {}: {}", key, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // cashu-mint spec 002 T011 — melt-saga binding pass-through
    // ---------------------------------------------------------------

    @Override
    public int insertOrClaimForHold(java.util.List<ProofEntity> proofs,
                                    String holdId,
                                    java.util.UUID mintId) {
        return DBProofVault.insertOrClaimForHold(mintId.toString(), holdId, proofs);
    }

    @Override
    public int markPendingForHold(java.util.Collection<String> proofSecrets,
                                  String holdId,
                                  java.util.UUID mintId) {
        return DBProofVault.markPendingForHold(
                mintId.toString(), holdId, java.util.List.copyOf(proofSecrets));
    }

    @Override
    public int commitSpentForHold(String holdId) {
        return DBProofVault.commitSpentForHold(holdId);
    }

    @Override
    public int refundForHold(String holdId) {
        return DBProofVault.refundForHold(holdId);
    }
}
