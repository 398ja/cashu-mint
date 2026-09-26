package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers a repeated {@code Y} lookup from memory for the duration of a single NUT-07 request.
 *
 * <p><b>Why this exists.</b> {@code /v1/checkstate} is served by
 * {@code CrossMintCheckStateMerger}, which runs one {@code CheckStateTask} per mint it serves, and
 * each task looks every requested {@code Y} up in the vault. The vault lookup behind
 * {@code retrieveProofByY} is {@code GET /proofs/{Y}}, keyed on the curve point <em>alone</em> with
 * no mint identifier, so every mint asks the vault the identical question and receives the
 * identical answer. Measured on staging with 20 genuinely distinct proofs: 120 vault GETs over 20
 * distinct keys, 6.0 GETs per {@code Y}, at roughly 4ms each and 18-26ms per proof end to end. The
 * round trips are the cost. Collapsing them to one GET per distinct {@code Y} is a measured 6x.
 *
 * <p><b>Why request-scoped and not shared.</b> A proof moves {@code UNSPENT -> PENDING -> SPENT}.
 * A cached {@code UNSPENT} that outlived its request would report a proof as spendable after it had
 * been spent, which is a double-spend window opened by the cache itself. The cache therefore holds
 * no state beyond one request, and gets its lifetime from being a plain object that the merger
 * constructs per {@code merge(...)} call and then drops: the scope boundary is the
 * {@code CrossMintCheckStateMerger.merge(...)} stack frame, which is entered once per HTTP request
 * and whose only reference to this instance dies with it. There is no static field, no Spring
 * scope, and no {@code ThreadLocal}, so there is nothing to leak if cleanup is forgotten and
 * nothing for a pooled handler thread to carry into the next request. A parameter was viable here,
 * so ambient state was not used.
 *
 * <p><b>Why it cannot reach the mutating paths.</b> The swap and melt paths
 * ({@code InvalidateProofsTask}, {@code SwapProofHold}) receive the {@code ProofVaultService} Spring
 * bean directly and have no reference to this wrapper, which only ever exists inside a checkstate
 * merge. Should this class nevertheless be wrapped around a mutating caller, every state-changing
 * method below discards the cache before delegating, so a read after a write cannot be answered
 * from a snapshot taken before it. That keeps the decorator substitutable for the service it wraps
 * rather than correct only by virtue of where it happens to be wired.
 *
 * <p>Not thread-safe, by construction: it is confined to the single thread running one merge, and a
 * {@link HashMap} is used precisely because it stores the {@code null} that a missing proof maps to.
 * Caching that {@code null} is the point, because the measured case is a proof the vault does not
 * hold, where all six lookups returned nothing.
 */
public class RequestScopedProofLookupCache implements ProofVaultService {

    private final ProofVaultService vault;

    /**
     * Proof found per requested {@code Y}, with a {@code null} value recording a {@code Y} the vault
     * does not hold. Membership is tested with {@code containsKey} so that a cached miss is a hit.
     */
    private final Map<String, ProofEntity> proofsByY = new HashMap<>();

    /**
     * Wraps the vault service whose {@code Y} lookups should be read once per request.
     *
     * @param vault the service to delegate to, never {@code null}
     */
    public RequestScopedProofLookupCache(@NonNull ProofVaultService vault) {
        this.vault = vault;
    }

    /**
     * Returns the proof recorded under {@code yHex}, consulting the vault only the first time each
     * distinct {@code yHex} is asked for within this request.
     *
     * @param yHex the hash-to-curve point supplied by the client
     * @return the stored proof, or {@code null} when no proof is recorded under that point
     * @throws CashuErrorException if the vault lookup fails
     */
    @Override
    public ProofEntity retrieveProofByY(String yHex) throws CashuErrorException {
        if (proofsByY.containsKey(yHex)) {
            return proofsByY.get(yHex);
        }
        ProofEntity found = vault.retrieveProofByY(yHex);
        proofsByY.put(yHex, found);
        return found;
    }

    /**
     * The number of distinct {@code Y} values this cache has answered from the vault. Lets a test
     * assert the collapse without reaching into the map.
     *
     * @return the count of vault lookups performed
     */
    public int lookupCount() {
        return proofsByY.size();
    }

    /** {@inheritDoc} */
    @Override
    public ProofEntity retrieveProof(UUID mintId, String secret) throws CashuErrorException {
        return vault.retrieveProof(mintId, secret);
    }

    /** {@inheritDoc} */
    @Override
    public String storageKeyFor(UUID mintId, String secret) throws CashuErrorException {
        return vault.storageKeyFor(mintId, secret);
    }





    /** {@inheritDoc} */
    @Override
    public int insertOrClaimForHold(List<ProofEntity> proofs, String holdId, UUID mintId)
            throws CashuErrorException {
        forgetCachedStates();
        return vault.insertOrClaimForHold(proofs, holdId, mintId);
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public int markPendingForHold(Collection<String> proofSecrets, String holdId, UUID mintId)
            throws CashuErrorException {
        forgetCachedStates();
        return vault.markPendingForHold(proofSecrets, holdId, mintId);
    }

    /** {@inheritDoc} */
    @Override
    public int commitSpentForHold(String holdId) throws CashuErrorException {
        forgetCachedStates();
        return vault.commitSpentForHold(holdId);
    }

    /** {@inheritDoc} */
    @Override
    public int refundForHold(String holdId) throws CashuErrorException {
        forgetCachedStates();
        return vault.refundForHold(holdId);
    }

    /**
     * Drops every cached state because a write is about to change what the vault would answer.
     * Only the caller knows which {@code Y} a mutation touches, and reporting a stale
     * {@code UNSPENT} is the dangerous direction of this error, so the whole snapshot goes.
     */
    private void forgetCachedStates() {
        proofsByY.clear();
    }
}
