package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.rest.service.CachingMintLoadService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code GET /v1/keys} costs the vault (#467).
 *
 * <p>This is the regression test for the outage. It does not assert on
 * response shape — {@code CashuControllerTraceTest} and friends cover that —
 * it asserts on <b>how many times the mint loaded from the vault</b>, because
 * that number is what took staging down: 2871 vault calls for 11 distinct
 * keys, ending in an {@code OutOfMemoryError} that killed the vault's HTTP
 * acceptor thread and wedged all minting.
 *
 * <p>The counter here is loads, not HTTP calls. One load is already O(keys)
 * calls against the real vault, so a test that allows "a few extra loads" is
 * not measuring the thing that matters.
 */
class CashuControllerKeysFanOutTest {

    /** Counts loads, standing in for the vault. */
    private static final class CountingLoadService implements MintLoadService {

        private final AtomicInteger loads = new AtomicInteger();
        private final List<String> activeIds;

        private CountingLoadService(List<String> activeIds) {
            this.activeIds = activeIds;
        }

        @Override
        public List<Mint> load(boolean archive) {
            loads.incrementAndGet();
            Mint mint = new Mint(UUID.randomUUID().toString());
            if (!archive) {
                for (String id : activeIds) {
                    mint.addKeySet(fakeKeySet(id));
                }
            }
            List<Mint> mints = new ArrayList<>(1);
            mints.add(mint);
            return mints;
        }

        @Override
        public Mint load(UUID mintId, boolean archive) {
            return new Mint(mintId.toString());
        }

        private static KeySet fakeKeySet(String id) {
            Keys keys = new Keys();
            keys.put(BigInteger.ONE, null);
            return KeySet.builder().id(id).unit("sat").keys(keys).build();
        }
    }

    /**
     * Staging's shape: three active keysets.
     *
     * <p>Before the fix this cost 2 loads for {@code activeKeySets()} plus 2
     * per keyset inside {@code LoadKeySetTask} — 8 full loads for this list,
     * growing as 2 + 2K. After it, the controller filters one load and the
     * cache serves the rest.
     */
    private static final List<String> THREE_ACTIVE =
            List.of("keyset-a", "keyset-b", "keyset-c");

    private static CashuController<Secret> controllerOver(MintLoadService loadService) {
        return new CashuController<>(null, loadService, null, null, null, null);
    }

    @Test
    @DisplayName("GET /v1/keys loads from the vault at most twice, whatever the keyset count")
    void keysDoesNotFanOutPerKeyset() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService(THREE_ACTIVE);
        CashuController<Secret> controller = controllerOver(new CachingMintLoadService(delegate, 60));

        KeySetResponse response = controller.allActiveKeys().getBody();

        assertThat(response).isNotNull();
        assertThat(response.getKeysets()).extracting(KeySet::getId)
                .containsExactly("keyset-a", "keyset-b", "keyset-c");

        // Two generations, one load each. The old code did 2 + 2*3 = 8.
        assertThat(delegate.loads).hasValue(2);
    }

    @Test
    @DisplayName("The cost does not grow with the number of active keysets")
    void costIsIndependentOfKeysetCount() throws CashuErrorException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("keyset-" + i);
        }

        CountingLoadService delegate = new CountingLoadService(many);
        CashuController<Secret> controller = controllerOver(new CachingMintLoadService(delegate, 60));

        KeySetResponse response = controller.allActiveKeys().getBody();

        assertThat(response).isNotNull();
        assertThat(response.getKeysets()).hasSize(40);

        // The old shape would be 2 + 2*40 = 82 full loads. This is the property
        // that actually prevents the outage: it is flat, not linear.
        assertThat(delegate.loads).hasValue(2);
    }

    @Test
    @DisplayName("Repeated requests within the TTL do not return to the vault")
    void repeatedRequestsAreServedFromCache() throws CashuErrorException {
        CountingLoadService delegate = new CountingLoadService(THREE_ACTIVE);
        CashuController<Secret> controller = controllerOver(new CachingMintLoadService(delegate, 60));

        for (int i = 0; i < 20; i++) {
            controller.allActiveKeys();
        }

        // A wallet bootstrapping calls this on every load; 20 clients must not
        // be 20x the vault traffic.
        assertThat(delegate.loads).hasValue(2);
    }

    @Test
    @DisplayName("Without the cache the controller is flat, not linear in keyset count")
    void controllerAloneDoesNotFanOut() throws CashuErrorException {
        CountingLoadService threeKeysets = new CountingLoadService(THREE_ACTIVE);
        controllerOver(threeKeysets).allActiveKeys();

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("keyset-" + i);
        }
        CountingLoadService fortyKeysets = new CountingLoadService(many);
        controllerOver(fortyKeysets).allActiveKeys();

        // Three loads uncached: activeKeySets() walks both generations, and the
        // controller's own keySets(false) is a third. Not two, as first assumed
        // -- the point is that it does not MOVE with the keyset count. The old
        // code was 2 + 2K, so these two cases would have been 8 and 82.
        assertThat(threeKeysets.loads).hasValue(3);
        assertThat(fortyKeysets.loads).hasValue(3);

        // The cache is what collapses the remaining 3 to 2; the controller fix
        // is what stops it growing. Each is pinned separately so a later change
        // to one does not silently depend on the other.
    }
}
