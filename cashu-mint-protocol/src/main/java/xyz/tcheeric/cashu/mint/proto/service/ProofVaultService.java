package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

public interface ProofVaultService {
    void store(ProofEntity proofEntity) throws CashuErrorException;
    void invalidate(ProofEntity proofEntity) throws CashuErrorException;
    void archive(ProofEntity proofEntity) throws CashuErrorException;
    void storePending(ProofEntity proofEntity) throws CashuErrorException;

    /**
     * Spec 005 — atomic insert-or-claim. Replaces the prior two-step
     * {@code storePending} + {@link #markPendingForHold} sequence
     * which could never bind freshly-inserted PENDING rows.
     *
     * <p>Submits Y-normalised {@link ProofEntity} rows; the vault
     * either claims an existing UNSPENT row or inserts a fresh row in
     * PENDING bound to {@code holdId}, in one round trip per
     * proof. Returns the total proof count actually bound. Callers
     * MUST compare against {@code proofs.size()} and refund any
     * partial holds before any external payment is attempted.
     *
     * <p>The default implementation is a no-op so legacy unit-test
     * contexts that don't wire a real vault keep compiling; the
     * production impl ({@code DefaultProofVaultService}) delegates to
     * the vault REST API.
     */
    default int insertOrClaimForHold(java.util.List<ProofEntity> proofs,
                                     String holdId,
                                     java.util.UUID mintId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — exclusive-hold proof binding for the melt saga
     * state machine. The default implementation is a no-op so legacy
     * callers don't break; the JPA-backed impl
     * ({@code DefaultProofVaultService}) is overridden to set
     * {@code hold_id} on the underlying proof rows.
     *
     * @return number of proof rows actually bound
     * @deprecated superseded by {@link #insertOrClaimForHold}; kept for
     *     in-flight callers and as the underlying primitive used by
     *     the saga reconciler. New code should use insert-or-claim.
     */
    @Deprecated
    default int markPendingForHold(java.util.Collection<String> proofSecrets,
                                   String holdId,
                                   java.util.UUID mintId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — commits saga's PENDING proofs as SPENT and clears
     * the {@code hold_id} binding. Called by {@code MeltTask} on
     * the {@code PAYMENT_SENT → COMPLETED} transition.
     */
    default int commitSpentForHold(String holdId) throws CashuErrorException {
        return 0;
    }

    /**
     * Spec 002 T011 — refunds saga's PENDING proofs back to UNSPENT
     * and clears the {@code hold_id} binding. Called by
     * {@code MeltTask} on the {@code PROOFS_HELD → FAILED} transition
     * and by the {@code MeltSagaReconciler}'s PROOFS_HELD TTL sweep.
     */
    default int refundForHold(String holdId) throws CashuErrorException {
        return 0;
    }

    /**
     * Look up a proof by its raw secret string (e.g. a 64-char hex random secret
     * or a serialised WellKnownSecret JSON), within the mint that issued it. The
     * implementation hashes the input with hash_to_curve to derive the storage key Y.
     *
     * <p>The mint is required rather than optional. A secret is only unique per mint: the
     * uniqueness constraint is {@code (mint_id, secret)}, so a global lookup can return another
     * mint's proof and let one mint read another's state as its own.
     *
     * <p>It is also the difference between an index scan and a full table scan. The only index
     * covering {@code secret} is {@code uk_proof_mint_secret (mint_id, secret)}, and a B-tree
     * cannot serve a predicate on its second column alone. Measured on staging at 13,011 rows:
     * 5 buffers and 0.076ms scoped, against 754 buffers and 2.4ms unscoped, and the unscoped cost
     * is linear in table size (16.3ms at 100k rows, 174ms at 1M) while the scoped form is flat.
     * See cashu-vault#153.
     */
    ProofEntity retrieveProof(UUID mintId, String secret) throws CashuErrorException;

    /**
     * Look up a proof when the caller already has the hash-to-curve point Y
     * (e.g. NUT-07 /v1/checkstate, which receives a list of Y values directly).
     * Skips the hash_to_curve step that {@link #retrieveProof(UUID, String)} applies,
     * so passing an already-hashed Y does NOT double-hash and silently miss.
     */
    ProofEntity retrieveProofByY(String yHex) throws CashuErrorException;

    /**
     * The key under which a proof with this secret must be recorded for this mint.
     *
     * <p>The NUT-00 secret encoding correction gave every secret two possible curve points, so a
     * proof recorded before the correction lives under the legacy point. Recording a later spend
     * of that same proof under the spec point would create a second row for one logical proof and
     * defeat the {@code (mint_id, secret)} uniqueness the double-spend check relies on. This
     * returns the key an existing record already uses, and otherwise the spec key.
     *
     * <p>Scoped to the mint for the same reasons as
     * {@link #retrieveProof(UUID, String)}: looking for the existing record across all
     * mints could adopt another mint's key, and it costs a full table scan.
     *
     * <p>The default implementation returns the spec key, which is correct for any deployment
     * that never issued a legacy proof.
     */
    default String storageKeyFor(UUID mintId, String secret) throws CashuErrorException {
        return SpentProofKey.issuanceKey(secret);
    }
}
