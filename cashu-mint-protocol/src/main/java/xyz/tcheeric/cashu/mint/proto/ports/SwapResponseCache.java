package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;

import java.util.Optional;

/**
 * Durable NUT-19 store for swap responses, keyed by {@link SwapRequestFingerprint} (issue #482).
 *
 * <p>The default methods make this a no-op, which is what {@code MintIntegrityContext} hands out
 * when no durable store is wired: unit-test and local contexts without a database keep working,
 * and a replay there is simply refused as it was before. Production refuses to boot without the
 * durable persistence layer, so the advertised cache is always backed there.
 */
public interface SwapResponseCache {

    /**
     * The unexpired response stored for a request, if any.
     *
     * @param fingerprint the request's cache key
     * @return the cached response, or empty when the request has not succeeded within the ttl
     */
    default Optional<CachedSwapResponse> find(SwapRequestFingerprint fingerprint) {
        return Optional.empty();
    }

    /**
     * Stores the response to a swap that succeeded, replayable until the configured ttl elapses.
     *
     * @param fingerprint  the request's cache key
     * @param responseJson the serialised {@code PostSwapResponse} the wallet was sent
     */
    default void store(SwapRequestFingerprint fingerprint, String responseJson) {
    }
}
