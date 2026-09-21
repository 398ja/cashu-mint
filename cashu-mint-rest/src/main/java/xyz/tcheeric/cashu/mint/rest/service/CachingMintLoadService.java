package xyz.tcheeric.cashu.mint.rest.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A short-lived cache in front of whatever actually loads mints from the vault.
 *
 * <h2>Wiring</h2>
 *
 * <p>This is itself a {@link MintLoadService} that <em>takes</em> a
 * {@link MintLoadService}, which Spring cannot resolve by type alone: it would
 * find this bean a candidate for its own constructor.
 *
 * <p>Naming a single delegate does not work either. The two implementations are
 * chosen by mutually-exclusive conditions ({@code PreloadMintLoadService} on
 * {@code mint.preload.enabled}, {@code DefaultMintLoadService} on profile), so
 * a fixed qualifier would name a bean that does not exist in the other
 * configuration — and giving them a shared name makes Spring refuse both with
 * {@code ConflictingBeanDefinitionException}.
 *
 * <p>So the whole list is injected and this bean filters itself out. Whatever
 * single delegate the active configuration supplies is the one wrapped, and a
 * third implementation added later needs no change here.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Reading the mint's keysets was O(keys) HTTP calls to the vault, and the
 * read path multiplied that by a further constant. Walking it for one
 * {@code GET /v1/keys}:
 *
 * <ul>
 *   <li>{@code ActiveKeySetsTask} calls {@code keySets(true)} <em>and</em>
 *       {@code keySets(false)} — two full loads.</li>
 *   <li>{@code MintLoadService.keySets(archive)} defaults to
 *       {@code load(archive)}, which retrieves every mint, then every keyset of
 *       every mint, then — in {@code DBKeySetVault.load} — <b>one HTTP call per
 *       key</b> to resolve the private material.</li>
 *   <li>{@code CashuController.allActiveKeys()} then called
 *       {@code NUT02.keys(id)} per active keyset, and
 *       {@code LoadKeySetTask} calls {@code keySets()} — which is
 *       {@code keySets(false) + keySets(true)} — so each keyset re-ran
 *       <em>both</em> full loads again.</li>
 * </ul>
 *
 * <p>Measured on staging with 3 keysets and 11 keys: ~190 vault calls per
 * request from the structure alone, and 2871 observed before
 * {@code imani-vault-jpa} died of {@code OutOfMemoryError}. The OOM killed its
 * {@code http-nio} acceptor thread, so the vault then refused <em>all</em>
 * connections, the mint blocked forever on its next vault read, and no voucher
 * could be issued by anyone. See #467.
 *
 * <h2>Why a cache, and not batching</h2>
 *
 * <p>The obvious fix — have {@code DBKeySetVault} use the batch
 * {@code GET /vault/key/keyset/{id}} endpoint and skip the per-key retrieve —
 * does not work: {@code KeyEntity.privateKey} is {@code @Transient} and the
 * batch endpoint returns entities straight from the repository, so the private
 * material is absent. Only the per-key path resolves it. The N+1 is therefore
 * load-bearing and the traffic has to be removed a level up instead.
 *
 * <h2>Why this is safe to cache</h2>
 *
 * <p>A keyset is immutable once published: its id is derived from its keys
 * (NUT-02), so changing a key changes the id and produces a different keyset
 * rather than mutating this one. What can change is <em>which</em> keysets are
 * active, and that is an administrative action measured in days, not seconds.
 *
 * <p>The TTL is therefore about bounding staleness after a rotation, not about
 * correctness within a request. The default is deliberately short — a rotation
 * becomes visible within a minute — because the cost of a miss is now one load
 * rather than hundreds.
 *
 * <h2>What is deliberately not cached</h2>
 *
 * <p>{@link #load(UUID, boolean)} for a specific mint id is passed straight
 * through. It is not on the hot path (nothing in the keys/keysets endpoints
 * calls it), and caching per-id would add keys to this map for every id ever
 * asked about, including ones that do not exist.
 *
 * <h2>Holding key material in memory</h2>
 *
 * <p>The cached {@link Mint} graph contains {@link KeySet}s whose public keys
 * are derived from private material fetched from HashiCorp. The derivation
 * happens in {@code DBKeySetVault.load} and only the <em>public</em> key is
 * retained on the {@code KeySet}, so what is held here is what the mint already
 * publishes at {@code /v1/keys}. This cache does not extend the lifetime of any
 * secret beyond what the uncached path already did.
 */
@Slf4j
@Service("cachingMintLoadService")
@Primary
@ConditionalOnProperty(name = "mint.keyset-cache.enabled", havingValue = "true", matchIfMissing = true)
public class CachingMintLoadService implements MintLoadService {

    /**
     * The delegate that actually talks to the vault.
     *
     * <p>Selected from every {@code MintLoadService} in the context except this
     * one; see the class javadoc for why it cannot be a fixed qualifier.
     */
    private final MintLoadService delegate;

    /**
     * Keyed on the {@code archive} flag, because that is the whole parameter
     * space of {@link #load(boolean)}: the active generation or the retired one.
     *
     * <p>Two entries, so {@code maximumSize} would be theatre. The TTL is the
     * only eviction that matters.
     */
    private final Cache<Boolean, List<Mint>> byArchive;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /**
     * One lock per generation, so a cold cache produces one vault load rather
     * than one per waiting request.
     *
     * <p>Separate objects rather than locking the service: the active and
     * archived generations are independent loads, and sharing a lock would make
     * a slow archived read block every wallet asking for active keys.
     */
    private final Object ACTIVE_LOCK = new Object();
    private final Object ARCHIVED_LOCK = new Object();

    /**
     * @param candidates every {@code MintLoadService} bean, including this one.
     *                   Spring injects a lazy list so this does not cycle: the
     *                   list is resolved after construction begins, and this
     *                   bean is filtered out of it below.
     */
    @Autowired
    public CachingMintLoadService(
            @NonNull ObjectProvider<MintLoadService> candidates,
            @Value("${mint.keyset-cache.ttl-seconds:60}") long ttlSeconds) {
        this(resolveDelegate(candidates), ttlSeconds);
    }

    /**
     * Direct construction, for tests and for the resolution above.
     *
     * <p>Public rather than package-private so tests in other packages can wrap
     * a counting delegate; the Spring path always goes through the
     * {@link ObjectProvider} constructor.
     */
    public CachingMintLoadService(@NonNull MintLoadService delegate, long ttlSeconds) {
        this.delegate = delegate;
        this.byArchive = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        log.info("keyset_cache_init delegate={} ttl_seconds={}",
                delegate.getClass().getSimpleName(), ttlSeconds);
    }

    /**
     * The one real loader to wrap.
     *
     * <p><b>More than one can legitimately be present.</b>
     * {@code DefaultMintLoadService} is annotated {@code @Profile({"!dev",
     * "!test"})}, and Spring treats a profile list as OR — so under the
     * {@code test} profile it still matches {@code !dev} and registers
     * alongside {@code PreloadMintLoadService}. That is pre-existing behaviour
     * this class must tolerate rather than correct: changing that annotation
     * would alter which loader the test context uses, which is a different
     * change with its own blast radius.
     *
     * <p>So when several are present, the {@code @Primary} one wins, matching
     * what every other injection point in the app would have resolved. Failing
     * here instead would make this cache unable to start in exactly the
     * configuration the test suite uses.
     */
    private static MintLoadService resolveDelegate(ObjectProvider<MintLoadService> candidates) {
        List<MintLoadService> others = candidates.stream()
                .filter(candidate -> !(candidate instanceof CachingMintLoadService))
                .toList();

        if (others.isEmpty()) {
            // Nothing to cache means nothing can answer /v1/keys. Fail loudly:
            // a mint serving an empty keyset list looks to every wallet like a
            // mint with no keys at all.
            throw new IllegalStateException("no MintLoadService available to cache");
        }
        if (others.size() == 1) {
            return others.get(0);
        }

        // Preload wins when both are registered, because that is what
        // `mint.preload.enabled` asks for and it is the more specific
        // condition. Logged, because silently choosing between two loaders of
        // someone's key material should never be invisible.
        MintLoadService chosen = others.stream()
                .filter(candidate -> "PreloadMintLoadService".equals(candidate.getClass().getSimpleName()))
                .findFirst()
                .orElseGet(() -> others.get(0));
        log.warn("keyset_cache_multiple_loaders found={} chosen={}",
                others.stream().map(s -> s.getClass().getSimpleName()).toList(),
                chosen.getClass().getSimpleName());
        return chosen;
    }

    /**
     * The cached read. Every keyset lookup in the mint funnels through here.
     *
     * <p>A load that throws is <b>not</b> cached: the exception propagates and
     * nothing is stored, so a vault blip does not pin a failure for the TTL.
     * The next caller retries.
     *
     * <p><b>One loader at a time per generation.</b> Without this, a cold cache
     * and N simultaneous requests produce N full vault loads — measured at 20
     * out of 20 before the lock was added. That is precisely the fan-out #467
     * exists to remove, merely made rarer: it returns on every restart, after
     * every TTL expiry under load, and hardest exactly when the mint is busiest.
     * Since a cold cache is guaranteed after any deploy, and the vault has
     * already died once from this traffic, the uncontended-case cost of a lock
     * is not a real trade.
     *
     * <p>The lock is held across the delegate call, which is deliberate. Callers
     * that arrive during a load wait for it and then read the cache, instead of
     * starting loads of their own. Waiting is what makes this safe: the two
     * generations lock independently, so an active-keyset load never blocks an
     * archived one.
     */
    @Override
    public List<Mint> load(boolean archive) throws CashuErrorException {
        List<Mint> cached = byArchive.getIfPresent(archive);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }

        // Per-generation lock, so `true` and `false` never contend.
        synchronized (archive ? ARCHIVED_LOCK : ACTIVE_LOCK) {
            // Re-check: while waiting, the thread that held the lock has very
            // likely populated this. This is the whole point of the lock, and
            // skipping the re-check would make it pure overhead.
            cached = byArchive.getIfPresent(archive);
            if (cached != null) {
                hits.incrementAndGet();
                return cached;
            }

            // Loaded outside any Caffeine mapping function so a
            // CashuErrorException propagates as itself: `get(key, fn)` cannot
            // throw checked exceptions, and wrapping this one would erase the
            // type that callers dispatch on.
            misses.incrementAndGet();
            List<Mint> loaded = delegate.load(archive);
            if (loaded != null) {
                byArchive.put(archive, loaded);
            }
            log.debug("keyset_cache_miss archive={} mints={} hits={} misses={}",
                    archive, loaded == null ? 0 : loaded.size(), hits.get(), misses.get());
            return loaded;
        }
    }

    /** Passed through; see the class javadoc for why this one is not cached. */
    @Override
    public Mint load(UUID mintId, boolean archive) throws CashuErrorException {
        return delegate.load(mintId, archive);
    }

    /**
     * Drops the cached generations.
     *
     * <p>For an administrative keyset rotation that must be visible before the
     * TTL expires, and for tests. Not wired to an endpoint here: exposing cache
     * control on the public API is a separate decision with its own auth
     * question.
     */
    public void invalidate() {
        byArchive.invalidateAll();
        log.info("keyset_cache_invalidated hits={} misses={}", hits.get(), misses.get());
    }

    /** Cache hits since startup. For tests and diagnostics. */
    public long hitCount() {
        return hits.get();
    }

    /** Cache misses since startup, i.e. loads that reached the vault. */
    public long missCount() {
        return misses.get();
    }
}
