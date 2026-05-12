package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface ProofVaultService {
    void store(ProofEntity proofEntity) throws CashuErrorException;
    void invalidate(ProofEntity proofEntity) throws CashuErrorException;
    void archive(ProofEntity proofEntity) throws CashuErrorException;
    void storePending(ProofEntity proofEntity) throws CashuErrorException;

    /**
     * Look up a proof by its raw secret string (e.g. a 64-char hex random secret
     * or a serialised WellKnownSecret JSON). The implementation hashes the input
     * with hash_to_curve to derive the storage key Y.
     */
    ProofEntity retrieveProof(String secret) throws CashuErrorException;

    /**
     * Look up a proof when the caller already has the hash-to-curve point Y
     * (e.g. NUT-07 /v1/checkstate, which receives a list of Y values directly).
     * Skips the hash_to_curve step that {@link #retrieveProof(String)} applies,
     * so passing an already-hashed Y does NOT double-hash and silently miss.
     */
    ProofEntity retrieveProofByY(String yHex) throws CashuErrorException;
}
