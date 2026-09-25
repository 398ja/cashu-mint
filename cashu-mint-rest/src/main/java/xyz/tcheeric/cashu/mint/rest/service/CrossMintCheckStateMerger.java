package xyz.tcheeric.cashu.mint.rest.service;

import lombok.NonNull;
import lombok.Value;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.impl.RequestScopedProofLookupCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merges NUT-07 proof states across every mint this deployment serves.
 *
 * <p>The merge is keyed on the <em>requested</em> {@code Ys} rather than on what the mints
 * happened to know, because NUT-07 requires one state per requested {@code Y}, in request
 * order. A shorter or reordered response cannot be lined up with the request, so a wallet
 * silently misreads which proof is spent.
 *
 * <p>Archived mints are consulted unconditionally: reporting a spent proof as unspent is the
 * dangerous direction of this error, and a proof spent at an archived mint is invisible to the
 * active ones.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/07.md">NUT-07</a>
 */
public class CrossMintCheckStateMerger {

    /**
     * Queries one mint for the state of the requested {@code Ys}. Named so the merge can be
     * tested without a vault behind {@link NUT07}.
     *
     * <p>The vault service is a parameter rather than something the query captures, because the
     * merge hands every mint the same request-scoped lookup cache. See {@link #merge}.
     */
    @FunctionalInterface
    interface MintCheckStateQuery {

        PostCheckStateResponse checkState(UUID mintId,
                                          PostCheckStateRequest request,
                                          ProofVaultService proofVaultService) throws CashuErrorException;
    }

    private final MintLoadService mintLoadService;

    private final MintCheckStateQuery mintCheckStateQuery;

    private final ProofVaultService proofVaultService;

    public CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService) {
        this(mintLoadService, new DefaultProofVaultService());
    }

    public CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService,
                                     @NonNull ProofVaultService proofVaultService) {
        this(mintLoadService, proofVaultService, CrossMintCheckStateMerger::checkStateAtMint);
    }

    CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService,
                              @NonNull MintCheckStateQuery mintCheckStateQuery) {
        this(mintLoadService, new DefaultProofVaultService(), mintCheckStateQuery);
    }

    CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService,
                              @NonNull ProofVaultService proofVaultService,
                              @NonNull MintCheckStateQuery mintCheckStateQuery) {
        this.mintLoadService = mintLoadService;
        this.proofVaultService = proofVaultService;
        this.mintCheckStateQuery = mintCheckStateQuery;
    }

    /**
     * Runs one mint's NUT-07 check against the supplied vault service, so the caller decides what
     * the task reads through. The remaining collaborators are the process-wide ones {@link NUT07}'s
     * own convenience overload would have created.
     */
    private static PostCheckStateResponse checkStateAtMint(UUID mintId,
                                                          PostCheckStateRequest request,
                                                          ProofVaultService proofVaultService)
            throws CashuErrorException {
        MintProtocolService mintProtocolService = MintProtocolServiceFactory.getInstance();
        MintVaultService mintVaultService = new DefaultMintVaultService();
        return NUT07.checkState(mintId, request, mintProtocolService, proofVaultService, mintVaultService);
    }

    /**
     * Checks every requested {@code Y} against all mints.
     *
     * <p><b>This method is the cache scope boundary.</b> The lookup cache is created here and
     * referenced only by this stack frame, so it dies when the merge returns, which is once per
     * {@code /v1/checkstate} request. That matters because a proof moves
     * {@code UNSPENT -> PENDING -> SPENT}: a cache that survived into the next request could report
     * a spent proof as spendable, so the fix for redundant reads must not become a double-spend
     * window. Within one merge the reuse is sound, because the vault lookup is keyed on the curve
     * point alone with no mint identifier, so the mints below are asking one question repeatedly
     * and receiving one answer. Measured on staging: 20 distinct proofs cost 120 vault GETs over 20
     * distinct keys, 6.0 per {@code Y}; this collapses that to 20.
     *
     * @param request the requested {@code Ys}
     * @return one state per requested {@code Y}, in request order
     * @throws CashuErrorException if a mint fails to answer
     */
    public PostCheckStateResponse merge(@NonNull PostCheckStateRequest request) throws CashuErrorException {
        ProofVaultService lookupCache = new RequestScopedProofLookupCache(proofVaultService);
        Map<String, ProofState> statesByY = new HashMap<>();
        for (Mint mint : allMints()) {
            mergeMintStates(statesByY, mint, request, lookupCache);
        }
        return respondInRequestOrder(request, statesByY);
    }

    private List<Mint> allMints() throws CashuErrorException {
        List<Mint> mints = new ArrayList<>();
        addAll(mints, mintLoadService.load(false));
        addAll(mints, mintLoadService.load(true));
        return mints;
    }

    private static void addAll(List<Mint> target, List<Mint> loaded) {
        if (loaded != null) {
            target.addAll(loaded);
        }
    }

    private void mergeMintStates(Map<String, ProofState> statesByY,
                                 Mint mint,
                                 PostCheckStateRequest request,
                                 ProofVaultService lookupCache) throws CashuErrorException {
        PostCheckStateResponse response =
                mintCheckStateQuery.checkState(UUID.fromString(mint.getId()), request, lookupCache);
        if (response == null || response.getStates() == null) {
            return;
        }
        for (PostCheckStateResponse.ResponseState state : response.getStates()) {
            if (state.getHashToCurveSecret() == null || state.getState() == null) {
                continue;
            }
            String key = state.getHashToCurveSecret().toString();
            ProofState candidate = new ProofState(state.getState(), state.getWitness());
            statesByY.merge(key, candidate, ProofState::mostAuthoritative);
        }
    }

    private static PostCheckStateResponse respondInRequestOrder(PostCheckStateRequest request,
                                                                Map<String, ProofState> statesByY) {
        PostCheckStateResponse response = new PostCheckStateResponse();
        for (HashToCurveSecret requested : request.getHashToCurveSecrets()) {
            ProofState found = statesByY.getOrDefault(requested.toString(), ProofState.unspent());
            PostCheckStateResponse.ResponseState responseState = new PostCheckStateResponse.ResponseState();
            responseState.setHashToCurveSecret(requested);
            responseState.setState(found.getState());
            responseState.setWitness(found.getWitness());
            response.addResponseState(responseState);
        }
        return response;
    }

    /**
     * A proof state as one mint reported it, with the NUT-10 witness that spent it.
     */
    @Value
    static class ProofState {

        String state;

        String witness;

        static ProofState unspent() {
            return new ProofState(NUT07.UNSPENT, null);
        }

        /**
         * Picks the state a wallet must act on: {@code SPENT} beats {@code PENDING} beats
         * {@code UNSPENT}. A witness is carried forward when the winner does not have one,
         * since only the mint that recorded the spend knows it.
         */
        ProofState mostAuthoritative(ProofState other) {
            ProofState winner = rank(other.state) > rank(state) ? other : this;
            String witness = winner.witness != null ? winner.witness
                    : (winner == this ? other.witness : this.witness);
            return new ProofState(winner.state, witness);
        }

        private static int rank(String state) {
            if (NUT07.SPENT.equals(state)) {
                return 2;
            }
            return NUT07.PENDING.equals(state) ? 1 : 0;
        }
    }
}
