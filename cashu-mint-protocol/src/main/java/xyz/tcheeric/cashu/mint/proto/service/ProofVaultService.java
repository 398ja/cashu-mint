package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface ProofVaultService {
    void store(ProofEntity proofEntity) throws CashuErrorException;
    void invalidate(ProofEntity proofEntity) throws CashuErrorException;
    void archive(ProofEntity proofEntity) throws CashuErrorException;
    void storePending(ProofEntity proofEntity) throws CashuErrorException;
    ProofEntity retrieveProof(String secret) throws CashuErrorException;
}
