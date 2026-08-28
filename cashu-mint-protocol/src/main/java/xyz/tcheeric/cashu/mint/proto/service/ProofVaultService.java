package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface ProofVaultService {
    void store(ProofEntity proofEntity) throws CashuErrorException;
    void invalidate(ProofEntity proofEntity) throws CashuErrorException;
    void archive(ProofEntity proofEntity) throws CashuErrorException;
    void storePending(ProofEntity proofEntity) throws CashuErrorException;

    /**
     * Spec 005 — atomic insert-or-claim. Replaces the prior two-step
     * {@code storePending} + {@link #markPendingForSaga} sequence
     * which could never bind freshly-inserted PENDING rows.
     *
     * <p>Submits Y-normalised {@link ProofEntity} rows; the vault
     * either claims an existing UNSPENT row or inserts a fresh row in
     * PENDING bound to {@code meltSagaId}, in one round trip per
     * proof. Returns the total proof count actually bound. Callers
     * MUST compare against {@code proofs.size()} and refund any
     * partial holds before any external payment is attempted.
     *
     * <p>The default implementation is a no-op so legacy unit-test
     * contexts that don't wire a real vault keep compiling; the
     * production impl ({@code DefaultProofVaultService}) delegates to
     * the vault REST API.
     */
    default int insertOrClaimForSaga(java.util.List<ProofEntity> proofs,
                                     String meltSagaId,
                                     java.util.UUID mintId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — exclusive-hold proof binding for the melt saga
     * state machine. The default implementation is a no-op so legacy
     * callers don't break; the JPA-backed impl
     * ({@code DefaultProofVaultService}) is overridden to set
     * {@code melt_saga_id} on the underlying proof rows.
     *
     * @return number of proof rows actually bound
     * @deprecated superseded by {@link #insertOrClaimForSaga}; kept for
     *     in-flight callers and as the underlying primitive used by
     *     the saga reconciler. New code should use insert-or-claim.
     */
    @Deprecated
    default int markPendingForSaga(java.util.Collection<String> proofSecrets,
                                   String meltSagaId,
                                   java.util.UUID mintId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — commits saga's PENDING proofs as SPENT and clears
     * the {@code melt_saga_id} binding. Called by {@code MeltTask} on
     * the {@code PAYMENT_SENT → COMPLETED} transition.
     */
    default int commitSpentForSaga(String meltSagaId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — refunds saga's PENDING proofs back to UNSPENT
     * and clears the {@code melt_saga_id} binding. Called by
     * {@code MeltTask} on the {@code PROOFS_HELD → FAILED} transition
     * and by the {@code MeltSagaReconciler}'s PROOFS_HELD TTL sweep.
     */
    default int refundForSaga(String meltSagaId) throws CashuErrorException {
        return 0;
    }

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

    /**
     * The key under which a proof with this secret must be recorded.
     *
     * <p>The NUT-00 secret encoding correction gave every secret two possible curve points, so a
     * proof recorded before the correction lives under the legacy point. Recording a later spend
     * of that same proof under the spec point would create a second row for one logical proof and
     * defeat the {@code (mint_id, secret)} uniqueness the double-spend check relies on. This
     * returns the key an existing record already uses, and otherwise the spec key.
     *
     * <p>The default implementation returns the spec key, which is correct for any deployment
     * that never issued a legacy proof.
     */
    default String storageKeyFor(String secret) throws CashuErrorException {
        return SpentProofKey.issuanceKey(secret);
    }
}
