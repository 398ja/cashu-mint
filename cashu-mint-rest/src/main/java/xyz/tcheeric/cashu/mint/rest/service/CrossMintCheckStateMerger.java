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
     */
    @FunctionalInterface
    interface MintCheckStateQuery {

        PostCheckStateResponse checkState(UUID mintId, PostCheckStateRequest request) throws CashuErrorException;
    }

    private final MintLoadService mintLoadService;

    private final MintCheckStateQuery mintCheckStateQuery;

    public CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService) {
        this(mintLoadService, NUT07::checkState);
    }

    CrossMintCheckStateMerger(@NonNull MintLoadService mintLoadService,
                              @NonNull MintCheckStateQuery mintCheckStateQuery) {
        this.mintLoadService = mintLoadService;
        this.mintCheckStateQuery = mintCheckStateQuery;
    }

    /**
     * Checks every requested {@code Y} against all mints.
     *
     * @param request the requested {@code Ys}
     * @return one state per requested {@code Y}, in request order
     * @throws CashuErrorException if a mint fails to answer
     */
    public PostCheckStateResponse merge(@NonNull PostCheckStateRequest request) throws CashuErrorException {
        Map<String, ProofState> statesByY = new HashMap<>();
        for (Mint mint : allMints()) {
            mergeMintStates(statesByY, mint, request);
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
                                 PostCheckStateRequest request) throws CashuErrorException {
        PostCheckStateResponse response = mintCheckStateQuery.checkState(UUID.fromString(mint.getId()), request);
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
