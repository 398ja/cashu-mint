package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.CheckStateTask;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Counts the vault reads a whole {@code /v1/checkstate} merge performs, through the real
 * {@link CheckStateTask}, because the redundancy being fixed is only visible once the merge fans the
 * same request out across every mint.
 *
 * <p>Measured on staging before this change: 20 distinct proofs cost 120 vault GETs over 20 distinct
 * keys, 6.0 GETs per {@code Y} at roughly 4ms each, and 18-26ms per proof. The fan-out is the cost,
 * so these tests assert the read count rather than a duration.
 */
class CrossMintCheckStateMergerVaultReadCountTest {

    private static final String Y_HELD_BY_THE_VAULT =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";

    private static final String Y_UNKNOWN_TO_THE_VAULT =
            "0244eccfc7a348274458bb38044c7f3c389b3c2086c7ec18b5812d2877ab937787";

    /** Mirrors the staging deployment, where six mints answer one checkstate request. */
    private static final int MINTS_CONSULTED = 6;

    @Test
    @DisplayName("Six mints asking for the same Y cost one vault read")
    // The measured defect, end to end: the merge runs one CheckStateTask per mint and each reads
    // every requested Y, but the vault lookup is keyed on the curve point with no mint id, so all
    // six mints ask one question. 120 GETs over 20 keys becomes 20.
    void sixMintsAskingForTheSameYCostOneVaultRead() throws CashuErrorException {
        ProofVaultService vault = vaultHolding(Y_HELD_BY_THE_VAULT);

        PostCheckStateResponse response = mergerOver(vault).merge(requestFor(Y_HELD_BY_THE_VAULT));

        verify(vault, times(1)).retrieveProof(StorageKey.of(Y_HELD_BY_THE_VAULT));
        assertThat(response.getStates()).hasSize(1);
        assertThat(response.getStates().get(0).getState()).isEqualTo(NUT07.SPENT);
    }

    @Test
    @DisplayName("A Y the vault does not hold also costs one read")
    // The common case measured on staging was a proof that does not exist, where all six lookups
    // returned null. Caching only hits would have left this case paying every round trip.
    void aYTheVaultDoesNotHoldAlsoCostsOneRead() throws CashuErrorException {
        ProofVaultService vault = vaultHolding(Y_HELD_BY_THE_VAULT);

        PostCheckStateResponse response = mergerOver(vault).merge(requestFor(Y_UNKNOWN_TO_THE_VAULT));

        verify(vault, times(1)).retrieveProof(StorageKey.of(Y_UNKNOWN_TO_THE_VAULT));
        assertThat(response.getStates().get(0).getState()).isEqualTo(NUT07.UNSPENT);
    }

    @Test
    @DisplayName("A repeated Y still gets its own response entry, in request order")
    // NUT-07 requires one state per requested Y, in request order, so a client sending a Y twice
    // must receive two entries. Only the lookups collapse; the response never does.
    void aRepeatedYStillGetsItsOwnResponseEntryInRequestOrder() throws CashuErrorException {
        ProofVaultService vault = vaultHolding(Y_HELD_BY_THE_VAULT);
        PostCheckStateRequest request =
                requestFor(Y_HELD_BY_THE_VAULT, Y_UNKNOWN_TO_THE_VAULT, Y_HELD_BY_THE_VAULT);

        PostCheckStateResponse response = mergerOver(vault).merge(request);

        assertThat(response.getStates())
                .extracting(state -> state.getHashToCurveSecret().toString())
                .containsExactly(Y_HELD_BY_THE_VAULT, Y_UNKNOWN_TO_THE_VAULT, Y_HELD_BY_THE_VAULT);
        assertThat(response.getStates())
                .extracting(PostCheckStateResponse.ResponseState::getState)
                .containsExactly(NUT07.SPENT, NUT07.UNSPENT, NUT07.SPENT);
        verify(vault, times(1)).retrieveProof(StorageKey.of(Y_HELD_BY_THE_VAULT));
        verify(vault, times(1)).retrieveProof(StorageKey.of(Y_UNKNOWN_TO_THE_VAULT));
    }

    @Test
    @DisplayName("A proof spent between two merges reads as spent in the second")
    // The security-relevant half, asserted at the boundary that owns the cache's lifetime. The cache
    // lives in merge(...), so the second request must re-read the vault. Were it shared, this proof
    // would still read UNSPENT after being spent, which is a double-spend window.
    void aProofSpentBetweenTwoMergesReadsAsSpentInTheSecond() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        when(vault.retrieveProof(StorageKey.of(Y_HELD_BY_THE_VAULT))).thenReturn(null);
        CrossMintCheckStateMerger merger = mergerOver(vault);

        PostCheckStateResponse beforeTheSpend = merger.merge(requestFor(Y_HELD_BY_THE_VAULT));
        assertThat(beforeTheSpend.getStates().get(0).getState()).isEqualTo(NUT07.UNSPENT);

        ProofEntity spent = proofInState(ProofEntity.STATE_SPENT);
        when(vault.retrieveProof(StorageKey.of(Y_HELD_BY_THE_VAULT))).thenReturn(spent);

        PostCheckStateResponse afterTheSpend = merger.merge(requestFor(Y_HELD_BY_THE_VAULT));
        assertThat(afterTheSpend.getStates().get(0).getState()).isEqualTo(NUT07.SPENT);

        verify(vault, times(2)).retrieveProof(StorageKey.of(Y_HELD_BY_THE_VAULT));
    }

    @Test
    @DisplayName("A mint returned by both generations is asked once, not twice")
    // DBMintVault.load(boolean) ignores its archived argument and answers every mint either way
    // (cashu-vault#145), so load(false) and load(true) return the SAME mints. The naive union ran
    // two CheckStateTasks per mint. This models that real vault behaviour, which the fixture above
    // does not: it hands the two generations distinct mints and so cannot see the duplication.
    void aMintReturnedByBothGenerationsIsAskedOnce() throws CashuErrorException {
        List<Mint> everyMintRegardlessOfGeneration =
                List.of(new Mint(UUID.randomUUID().toString()), new Mint(UUID.randomUUID().toString()));
        List<UUID> mintsAsked = new ArrayList<>();

        mergerOverBothGenerations(everyMintRegardlessOfGeneration, mintsAsked)
                .merge(requestFor(Y_UNKNOWN_TO_THE_VAULT));

        assertThat(mintsAsked).hasSize(2);
        assertThat(mintsAsked).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Genuinely distinct mints are all still consulted")
    // The other direction, because de-duplicating by id could silently drop a mint that should have
    // been asked. A proof lives in exactly one mint, so losing one loses its state.
    void genuinelyDistinctMintsAreAllStillConsulted() throws CashuErrorException {
        List<UUID> mintsAsked = new ArrayList<>();

        mergerOver(vaultHolding(Y_HELD_BY_THE_VAULT), mintsAsked)
                .merge(requestFor(Y_HELD_BY_THE_VAULT));

        assertThat(mintsAsked).hasSize(MINTS_CONSULTED);
        assertThat(mintsAsked).doesNotHaveDuplicates();
    }

    /**
     * A merger over {@link #MINTS_CONSULTED} mints that runs the real {@link CheckStateTask}, so the
     * vault read count reflects the production lookup chain rather than a stand-in for it.
     */
    private static CrossMintCheckStateMerger mergerOver(ProofVaultService vault) {
        return mergerOver(vault, new ArrayList<>());
    }

    /** As {@link #mergerOver(ProofVaultService)}, recording which mint each task was run for. */
    private static CrossMintCheckStateMerger mergerOver(ProofVaultService vault, List<UUID> mintsAsked) {
        return new CrossMintCheckStateMerger(mintLoadService(), vault,
                (mintId, request, lookupCache) -> {
                    mintsAsked.add(mintId);
                    return new CheckStateTask(
                            mintId, request, mintProtocolService(), lookupCache,
                            Mockito.mock(MintVaultService.class))
                            .execute();
                });
    }

    /**
     * A merger whose load service answers the same mints for both generations, reproducing
     * {@code DBMintVault.load(boolean)} ignoring its argument.
     */
    private static CrossMintCheckStateMerger mergerOverBothGenerations(List<Mint> everyMint,
                                                                      List<UUID> mintsAsked)
            throws CashuErrorException {
        MintLoadService loadService = new MintLoadService() {
            @Override
            public Mint load(UUID mintId, boolean archive) {
                return new Mint(mintId.toString());
            }

            @Override
            public List<Mint> load(boolean archive) {
                return everyMint;
            }
        };
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        when(vault.retrieveProof(StorageKey.of(Y_UNKNOWN_TO_THE_VAULT))).thenReturn(null);
        return new CrossMintCheckStateMerger(loadService, vault,
                (mintId, request, lookupCache) -> {
                    mintsAsked.add(mintId);
                    return new CheckStateTask(
                            mintId, request, mintProtocolService(), lookupCache,
                            Mockito.mock(MintVaultService.class))
                            .execute();
                });
    }

    private static ProofVaultService vaultHolding(String y) throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        // The stub value is built first: stubbing a mock inside a when(...) argument leaves the
        // outer stubbing unfinished and Mockito rejects it.
        ProofEntity spent = proofInState(ProofEntity.STATE_SPENT);
        when(vault.retrieveProof(StorageKey.of(y))).thenReturn(spent);
        return vault;
    }

    private static ProofEntity proofInState(String state) {
        ProofEntity proof = Mockito.mock(ProofEntity.class);
        when(proof.getState()).thenReturn(state);
        return proof;
    }

    private static MintProtocolService mintProtocolService() {
        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        when(mintProtocolService.toMintEntity(any(Mint.class))).thenReturn(Mockito.mock(MintEntity.class));
        return mintProtocolService;
    }

    /** One active mint and the remaining archived ones, so the merge fans out over all six. */
    private static MintLoadService mintLoadService() {
        List<Mint> active = List.of(new Mint(UUID.randomUUID().toString()));
        List<Mint> archived = new ArrayList<>();
        for (int remaining = 1; remaining < MINTS_CONSULTED; remaining++) {
            archived.add(new Mint(UUID.randomUUID().toString()));
        }
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

    private static PostCheckStateRequest requestFor(String... ys) {
        List<HashToCurveSecret> secrets = new ArrayList<>(ys.length);
        for (String y : ys) {
            secrets.add(HashToCurveSecret.fromString(y));
        }
        return new PostCheckStateRequest(secrets);
    }
}
