package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache that stopped {@code /v1/keys} taking the vault down (#467).
 *
 * <p>The defect was not subtle once traced: loading keysets is O(keys) HTTP
 * calls to the vault, and the read path ran that load a dozen times per
 * request. On staging that was 2871 vault calls for 11 distinct keys, which
 * OOM'd the vault and wedged all minting. These tests pin the two properties
 * that matter — the traffic is bounded, and bounding it did not change what
 * callers see.
 */
class CachingMintLoadServiceTest {

    /**
     * A delegate that counts loads, standing in for the vault.
     *
     * <p>Counting is the point: correctness here is "how many times did we go
     * to the vault", which no assertion on the returned value can capture.
     */
    private static final class CountingLoadService implements MintLoadService {

        private final AtomicInteger listLoads = new AtomicInteger();
        private final AtomicInteger idLoads = new AtomicInteger();
        private CashuErrorException failWith;

        @Override
        public List<Mint> load(boolean archive) throws CashuErrorException {
            listLoads.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            Mint mint = new Mint(UUID.randomUUID().toString());
            mint.addKeySet(fakeKeySet(archive ? "archived-keyset" : "active-keyset"));
            List<Mint> mints = new ArrayList<>(1);
            mints.add(mint);
            return mints;
        }

        @Override
        public Mint load(UUID mintId, boolean archive) throws CashuErrorException {
            idLoads.incrementAndGet();
            return new Mint(mintId.toString());
        }

        private static KeySet fakeKeySet(String id) {
            Keys keys = new Keys();
            keys.put(BigInteger.ONE, null);
            return KeySet.builder().id(id).unit("sat").keys(keys).build();
        }
    }

    private static CachingMintLoadService cachingOver(CountingLoadService delegate) {
        return new CachingMintLoadService(delegate, 60);
    }

    @Test
    @DisplayName("Repeated loads of the same generation hit the vault once")
    void repeatedLoadsHitTheVaultOnce() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = cachingOver(delegate);

        for (int i = 0; i < 50; i++) {
            caching.load(false);
        }

        assertThat(delegate.listLoads).hasValue(1);
        assertThat(caching.hitCount()).isEqualTo(49);
        assertThat(caching.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Active and archived generations are cached separately")
    void generationsAreCachedSeparately() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = cachingOver(delegate);

        // ActiveKeySetsTask asks for both, so conflating them would return the
        // wrong generation for one of the two callers.
        caching.load(false);
        caching.load(true);
        caching.load(false);
        caching.load(true);

        assertThat(delegate.listLoads).hasValue(2);

        assertThat(caching.load(false).get(0).getKeySets())
                .extracting(KeySet::getId)
                .containsExactly("active-keyset");
        assertThat(caching.load(true).get(0).getKeySets())
                .extracting(KeySet::getId)
                .containsExactly("archived-keyset");
    }

    @Test
    @DisplayName("keySets() walks both generations but still loads each once")
    void keySetsWalksBothGenerationsOnce() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = cachingOver(delegate);

        // This is the call LoadKeySetTask makes. Uncached it is two full loads
        // EVERY time, which is what multiplied the fan-out per keyset.
        caching.keySets();
        caching.keySets();
        caching.keySets();

        assertThat(delegate.listLoads).hasValue(2);
        assertThat(caching.keySets()).extracting(KeySet::getId)
                .containsExactlyInAnyOrder("active-keyset", "archived-keyset");
    }

    @Test
    @DisplayName("A failed load is not cached, so the next caller retries")
    void aFailedLoadIsNotCached() {
        CountingLoadService delegate = new CountingLoadService();
        delegate.failWith = new CashuErrorException("vault unreachable");
        CachingMintLoadService caching = cachingOver(delegate);

        assertThatThrownBy(() -> caching.load(false)).isInstanceOf(CashuErrorException.class);
        assertThatThrownBy(() -> caching.load(false)).isInstanceOf(CashuErrorException.class);

        // Caching the failure would pin the mint in a broken state for the whole
        // TTL after a momentary vault blip -- the opposite of what this is for.
        assertThat(delegate.listLoads).hasValue(2);

        delegate.failWith = null;
        assertThat(caching.hitCount()).isZero();
    }

    @Test
    @DisplayName("A CashuErrorException propagates as itself, not wrapped")
    void errorTypeIsPreserved() {
        CountingLoadService delegate = new CountingLoadService();
        CashuErrorException original = new CashuErrorException("vault unreachable");
        delegate.failWith = original;
        CachingMintLoadService caching = cachingOver(delegate);

        // Callers dispatch on type. Wrapping this in a RuntimeException to fit
        // Caffeine's mapping function would erase that, which is why the load
        // happens outside the cache's loader.
        assertThatThrownBy(() -> caching.load(false)).isSameAs(original);
    }

    @Test
    @DisplayName("Invalidation forces the next load back to the vault")
    void invalidationForcesAReload() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = cachingOver(delegate);

        caching.load(false);
        caching.load(false);
        assertThat(delegate.listLoads).hasValue(1);

        // The escape hatch for an administrative rotation that must be visible
        // before the TTL expires.
        caching.invalidate();
        caching.load(false);

        assertThat(delegate.listLoads).hasValue(2);
    }

    @Test
    @DisplayName("A zero TTL disables caching rather than caching forever")
    void zeroTtlDisablesCaching() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = new CachingMintLoadService(delegate, 0);

        caching.load(false);
        caching.load(false);
        caching.load(false);

        // Worth pinning: an operator setting 0 to "turn the cache off" must not
        // get an immortal cache instead.
        assertThat(delegate.listLoads).hasValue(3);
    }

    @Test
    @DisplayName("Loading one mint by id is passed straight through")
    void loadByIdIsNotCached() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService();
        CachingMintLoadService caching = cachingOver(delegate);

        UUID id = UUID.randomUUID();
        caching.load(id, false);
        caching.load(id, false);

        // Not on the hot path, and caching per-id would grow an entry for every
        // id ever asked about, including ones that do not exist.
        assertThat(delegate.idLoads).hasValue(2);
    }

    /**
     * A cold cache under concurrent load must produce ONE vault load, not one per request.
     *
     * <p>This is the failure #467 is about, in its most dangerous form. Caching alone makes the
     * fan-out rarer without removing it: every restart begins cold, wallets reconnect together,
     * and each concurrent miss starts its own full load — measured at <b>20 vault loads for 20
     * requests</b> before the per-generation lock existed. The vault has already died once of
     * exactly this traffic, taking its HTTP acceptor thread with it and leaving the whole stack
     * unable to mint.
     *
     * <p>The delegate sleeps so the requests genuinely overlap; without that they would queue
     * naturally and the test would pass whether or not the lock is there.
     */
    @Test
    @DisplayName("a cold cache under concurrent load triggers one vault load, not one per caller")
    void concurrentMissesCollapseToOneLoad() throws Exception {
        AtomicInteger vaultLoads = new AtomicInteger();
        MintLoadService slowDelegate = new MintLoadService() {
            @Override
            public List<Mint> load(boolean archive) {
                vaultLoads.incrementAndGet();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public Mint load(UUID mintId, boolean archive) {
                return null;
            }
        };

        CachingMintLoadService cache = new CachingMintLoadService(slowDelegate, 60);

        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<List<Mint>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    startTogether.await();
                    return cache.load(false);
                }));
            }
            startTogether.countDown();
            for (Future<List<Mint>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(vaultLoads.get())
                .describedAs("%d concurrent cold-cache requests must collapse to a single "
                        + "vault load; one load per caller is the #467 fan-out returning "
                        + "on every restart", callers)
                .isEqualTo(1);
    }

    /**
     * The two generations must not block each other.
     *
     * <p>A single shared lock would also pass the test above while making a slow archived-keyset
     * read stall every wallet asking for active keys — trading a thundering herd for a queue.
     */
    @Test
    @DisplayName("active and archived loads do not contend")
    void generationsLoadIndependently() throws Exception {
        CountDownLatch archivedStarted = new CountDownLatch(1);
        CountDownLatch releaseArchived = new CountDownLatch(1);

        MintLoadService blockingArchive = new MintLoadService() {
            @Override
            public List<Mint> load(boolean archive) {
                if (archive) {
                    archivedStarted.countDown();
                    try {
                        releaseArchived.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return List.of();
            }

            @Override
            public Mint load(UUID mintId, boolean archive) {
                return null;
            }
        };

        CachingMintLoadService cache = new CachingMintLoadService(blockingArchive, 60);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> cache.load(true));
            assertThat(archivedStarted.await(10, TimeUnit.SECONDS))
                    .describedAs("archived load should have started")
                    .isTrue();

            // Must complete while the archived load is still held open.
            Future<List<Mint>> active = pool.submit(() -> cache.load(false));
            assertThat(active.get(10, TimeUnit.SECONDS))
                    .describedAs("an active load must not wait behind a slow archived one")
                    .isNotNull();
        } finally {
            releaseArchived.countDown();
            pool.shutdownNow();
        }
    }
}
