package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface ProofVaultService {
    void store(ProofEntity proofEntity);
    void invalidate(ProofEntity proofEntity);
    void archive(ProofEntity proofEntity);
    void storePending(ProofEntity proofEntity);
    ProofEntity retrieveProof(String secret);
}
