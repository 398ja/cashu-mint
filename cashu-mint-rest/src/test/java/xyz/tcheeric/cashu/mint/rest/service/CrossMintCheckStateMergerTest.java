package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CrossMintCheckStateMergerTest {

    private static final String Y_UNSPENT_AT_ACTIVE_MINT =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";

    private static final String Y_SPENT_AT_ARCHIVED_MINT =
            "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";

    private static final String Y_UNKNOWN_TO_EVERY_MINT =
            "0244eccfc7a348274458bb38044c7f3c389b3c2086c7ec18b5812d2877ab937787";

    private final String activeMintId = UUID.randomUUID().toString();

    private final String archivedMintId = UUID.randomUUID().toString();

    @Test
    @DisplayName("Returns one state per requested Y, in request order")
    // NUT-07 requires states to line up with Ys; an unknown Y must appear as UNSPENT rather than vanish.
    void returnsOneStatePerRequestedYInRequestOrder() throws CashuErrorException {
        PostCheckStateRequest request = requestFor(
                Y_UNKNOWN_TO_EVERY_MINT, Y_SPENT_AT_ARCHIVED_MINT, Y_UNSPENT_AT_ACTIVE_MINT);

        PostCheckStateResponse response = merger().merge(request);

        assertThat(response.getStates()).hasSameSizeAs(request.getHashToCurveSecrets());
        assertThat(response.getStates())
                .extracting(state -> state.getHashToCurveSecret().toString())
                .containsExactly(Y_UNKNOWN_TO_EVERY_MINT, Y_SPENT_AT_ARCHIVED_MINT, Y_UNSPENT_AT_ACTIVE_MINT);
        assertThat(response.getStates().get(0).getState()).isEqualTo(NUT07.UNSPENT);
    }

    @Test
    @DisplayName("Consults archived mints even when an active mint answered")
    // A proof spent at an archived mint must not read UNSPENT just because an active mint knows another Y.
    void consultsArchivedMintsEvenWhenAnActiveMintAnswered() throws CashuErrorException {
        PostCheckStateResponse response = merger()
                .merge(requestFor(Y_UNSPENT_AT_ACTIVE_MINT, Y_SPENT_AT_ARCHIVED_MINT));

        assertThat(response.getStates().get(0).getState()).isEqualTo(NUT07.UNSPENT);
        assertThat(response.getStates().get(1).getState()).isEqualTo(NUT07.SPENT);
    }

    @Test
    @DisplayName("Carries the witness of the spending mint through the merge")
    // Offline validation of a spent P2PK proof needs the witness, which the old map-of-state merge dropped.
    void carriesTheWitnessOfTheSpendingMintThroughTheMerge() throws CashuErrorException {
        PostCheckStateResponse response = merger().merge(requestFor(Y_SPENT_AT_ARCHIVED_MINT));

        assertThat(response.getStates().get(0).getWitness()).isEqualTo("{\"signatures\":[\"deadbeef\"]}");
    }

    @Test
    @DisplayName("Prefers SPENT over PENDING over UNSPENT across mints")
    // The most authoritative state wins, so a proof spent anywhere is never reported as merely pending.
    void prefersSpentOverPendingOverUnspent() throws CashuErrorException {
        MintLoadService mintLoadService = mintLoadService(List.of(new Mint(activeMintId)), List.of(new Mint(archivedMintId)));
        CrossMintCheckStateMerger merger = new CrossMintCheckStateMerger(mintLoadService, (mintId, request) ->
                responseOf(request, activeMintId.equals(mintId.toString())
                        ? Map.of(Y_SPENT_AT_ARCHIVED_MINT, state(NUT07.PENDING, null))
                        : Map.of(Y_SPENT_AT_ARCHIVED_MINT, state(NUT07.SPENT, "witness"))));

        PostCheckStateResponse response = merger.merge(requestFor(Y_SPENT_AT_ARCHIVED_MINT));

        assertThat(response.getStates().get(0).getState()).isEqualTo(NUT07.SPENT);
        assertThat(response.getStates().get(0).getWitness()).isEqualTo("witness");
    }

    private CrossMintCheckStateMerger merger() {
        MintLoadService mintLoadService =
                mintLoadService(List.of(new Mint(activeMintId)), List.of(new Mint(archivedMintId)));
        return new CrossMintCheckStateMerger(mintLoadService, (mintId, request) -> {
            Map<String, PostCheckStateResponse.ResponseState> known = new LinkedHashMap<>();
            if (activeMintId.equals(mintId.toString())) {
                known.put(Y_UNSPENT_AT_ACTIVE_MINT, state(NUT07.UNSPENT, null));
            } else {
                known.put(Y_SPENT_AT_ARCHIVED_MINT, state(NUT07.SPENT, "{\"signatures\":[\"deadbeef\"]}"));
            }
            return responseOf(request, known);
        });
    }

    private static MintLoadService mintLoadService(List<Mint> active, List<Mint> archived) {
        return new MintLoadService() {
            @Override
            public Mint load(UUID mintId, boolean archive) {
                return new Mint(mintId.toString());
            }

            @Override
            public List<Mint> load(boolean archive) {
                return archive ? archived : active;
            }
        };
    }

    /** Mirrors CheckStateTask: every requested Y comes back, unknown ones as UNSPENT. */
    private static PostCheckStateResponse responseOf(PostCheckStateRequest request,
                                                     Map<String, PostCheckStateResponse.ResponseState> known) {
        PostCheckStateResponse response = new PostCheckStateResponse();
        for (HashToCurveSecret requested : request.getHashToCurveSecrets()) {
            PostCheckStateResponse.ResponseState found = known.get(requested.toString());
            PostCheckStateResponse.ResponseState responseState = new PostCheckStateResponse.ResponseState();
            responseState.setHashToCurveSecret(requested);
            responseState.setState(found == null ? NUT07.UNSPENT : found.getState());
            responseState.setWitness(found == null ? null : found.getWitness());
            response.addResponseState(responseState);
        }
        return response;
    }

    private static PostCheckStateResponse.ResponseState state(String state, String witness) {
        PostCheckStateResponse.ResponseState responseState = new PostCheckStateResponse.ResponseState();
        responseState.setState(state);
        responseState.setWitness(witness);
        return responseState;
    }

    private static PostCheckStateRequest requestFor(String... ys) {
        List<HashToCurveSecret> secrets = new ArrayList<>(ys.length);
        for (String y : ys) {
            secrets.add(HashToCurveSecret.fromString(y));
        }
        return new PostCheckStateRequest(secrets);
    }
}
