package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.service.impl.RequestScopedProofLookupCache;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the two properties the request-scoped proof lookup cache has to hold at once: it must stop
 * repeating a vault read within one request, and it must never answer a later request from the
 * earlier request's snapshot.
 */
class RequestScopedProofLookupCacheTest {

    /** Proof lookups are scoped per mint (cashu-vault#153). */
    private static final java.util.UUID MINT_ID =
            java.util.UUID.fromString("1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae");

    private static final StorageKey Y =
            StorageKey.of("02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee");

    private static final StorageKey OTHER_Y =
            StorageKey.of("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2");

    @Test
    @DisplayName("Repeating a Y within one request reads the vault once")
    // The measured defect: the six mints CrossMintCheckStateMerger consults each ask the vault for
    // the same Y, because the vault lookup is keyed on the curve point with no mint id. Six reads of
    // one key at ~4ms each is the per-proof cost, so the repeat must collapse to a single read.
    void repeatingAYWithinOneRequestReadsTheVaultOnce() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        ProofEntity spent = spentProof();
        when(vault.retrieveProof(Y)).thenReturn(spent);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);

        for (int mintConsulted = 0; mintConsulted < 6; mintConsulted++) {
            assertThat(cache.retrieveProof(Y)).isSameAs(spent);
        }

        verify(vault, times(1)).retrieveProof(Y);
        assertThat(cache.lookupCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Caches the absence of a proof, not only a hit")
    // The common case is a Y the vault does not hold, where all six lookups returned null. Caching
    // only hits would leave that case paying the full six round trips.
    void cachesTheAbsenceOfAProofNotOnlyAHit() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        when(vault.retrieveProof(Y)).thenReturn(null);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);

        for (int mintConsulted = 0; mintConsulted < 6; mintConsulted++) {
            assertThat(cache.retrieveProof(Y)).isNull();
        }

        verify(vault, times(1)).retrieveProof(Y);
    }

    @Test
    @DisplayName("Reads the vault once per distinct Y")
    // Distinct Ys are distinct questions, so the cache must not conflate them into one answer.
    void readsTheVaultOncePerDistinctY() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        ProofEntity spent = spentProof();
        when(vault.retrieveProof(Y)).thenReturn(spent);
        when(vault.retrieveProof(OTHER_Y)).thenReturn(null);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);

        assertThat(cache.retrieveProof(Y)).isSameAs(spent);
        assertThat(cache.retrieveProof(OTHER_Y)).isNull();
        assertThat(cache.retrieveProof(Y)).isSameAs(spent);
        assertThat(cache.retrieveProof(OTHER_Y)).isNull();

        verify(vault, times(1)).retrieveProof(Y);
        verify(vault, times(1)).retrieveProof(OTHER_Y);
    }

    @Test
    @DisplayName("A proof spent between two requests reads as spent in the second")
    // The security-relevant half. Each request gets its own cache, so a proof that was unspent when
    // the first request asked must not still read unspent after it has been spent. A cache that
    // outlived its request would report it spendable and open a double-spend window.
    void aProofSpentBetweenTwoRequestsReadsAsSpentInTheSecond() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        when(vault.retrieveProof(Y)).thenReturn(null);

        RequestScopedProofLookupCache firstRequest = new RequestScopedProofLookupCache(vault);
        assertThat(firstRequest.retrieveProof(Y)).isNull();

        ProofEntity spent = spentProof();
        when(vault.retrieveProof(Y)).thenReturn(spent);

        RequestScopedProofLookupCache secondRequest = new RequestScopedProofLookupCache(vault);
        assertThat(secondRequest.retrieveProof(Y)).isSameAs(spent);

        verify(vault, times(2)).retrieveProof(Y);
    }

    @Test
    @DisplayName("A read after a write is not answered from the pre-write snapshot")
    // The cache is only ever wired into the read-only checkstate path, but a decorator has to remain
    // substitutable for the service it wraps. Serving a state cached before a mutation would be a
    // correctness bug rather than a cache hit, so a write discards the snapshot.
    void aReadAfterAWriteIsNotAnsweredFromThePreWriteSnapshot() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        when(vault.retrieveProof(Y)).thenReturn(null);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);
        assertThat(cache.retrieveProof(Y)).isNull();

        ProofEntity spent = spentProof();
        cache.commitSpentForHold("hold");
        when(vault.retrieveProof(Y)).thenReturn(spent);

        assertThat(cache.retrieveProof(Y)).isSameAs(spent);
        verify(vault, times(2)).retrieveProof(Y);
    }

    @Test
    @DisplayName("Secret-keyed lookups are passed straight through")
    // Only the Y-keyed lookup is cached. retrieveProof hashes its input and is used by the mutating
    // swap and melt paths, so caching it would put a snapshot where state is changing.
    void secretKeyedLookupsArePassedStraightThrough() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        ProofSecret secret = new ProofSecret("secret");
        when(vault.retrieveProof(MINT_ID, secret)).thenReturn(null);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);

        cache.retrieveProof(MINT_ID, secret);
        cache.retrieveProof(MINT_ID, secret);

        verify(vault, times(2)).retrieveProof(MINT_ID, secret);
        verify(vault, never()).retrieveProof(any(StorageKey.class));
    }

    @Test
    @DisplayName("The same Y in either hex case is one cache entry")
    // StorageKey normalises to lowercase, the form the vault stores, so a client that sends an
    // uppercase Y is served the same answer and does not cost a second read.
    void theSameYInEitherHexCaseIsOneCacheEntry() throws CashuErrorException {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        RequestScopedProofLookupCache cache = new RequestScopedProofLookupCache(vault);

        cache.retrieveProof(Y);
        cache.retrieveProof(StorageKey.of(Y.hex().toUpperCase(Locale.ROOT)));

        verify(vault, times(1)).retrieveProof(Y);
    }

    private static ProofEntity spentProof() {
        ProofEntity proof = Mockito.mock(ProofEntity.class);
        when(proof.getState()).thenReturn(ProofEntity.STATE_SPENT);
        return proof;
    }
}
