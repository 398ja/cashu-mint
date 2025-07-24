package xyz.tcheeric.cashu.mint.proto.vault;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface ProofRepository {
    ProofEntity retrieveProof(String secret) throws CashuErrorException;
    void store(ProofEntity entity) throws CashuErrorException;
    void storePending(ProofEntity entity) throws CashuErrorException;
    void invalidate(ProofEntity entity) throws CashuErrorException;
    void archive(ProofEntity entity) throws CashuErrorException;
}
