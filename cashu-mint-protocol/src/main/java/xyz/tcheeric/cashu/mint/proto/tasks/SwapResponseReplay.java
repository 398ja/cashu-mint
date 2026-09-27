package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;

import java.util.Optional;

/**
 * NUT-19 replay for one {@code POST /v1/swap} request (issue #482).
 *
 * <p>A swap that succeeded is remembered under its {@link SwapRequestFingerprint}, and the same
 * request arriving again is answered from that record instead of being processed. Processing it
 * again could only fail: its inputs are spent and its outputs already signed, so the wallet would
 * get an error for a swap that in fact went through.
 *
 * <p>The cache is an aid to recovery, never a condition of the swap, so neither direction lets a
 * cache failure decide the outcome of a swap:
 * <ul>
 *   <li>A lookup that fails is treated as a miss. A fresh swap then proceeds normally, and a replay
 *       is refused exactly as it was before the cache existed.</li>
 *   <li>A store that fails is logged and the response still returned. The swap has spent its
 *       inputs and signed its outputs by then, so turning the bookkeeping failure into an error
 *       would lose the wallet the very signatures the cache exists to protect. Those outputs stay
 *       recoverable through NUT-09 restore.</li>
 * </ul>
 *
 * <p>A replay is not validated again, so a replay carrying a missing or wrong NUT-11 witness is
 * still answered. That hands out nothing new: the signatures are over the same {@code B_} values
 * and only the holder of their blinding factors can unblind them.
 *
 * <p>{@code CachedEndpoint.SWAP} names {@link #previousResponse()} as its witness, so removing
 * this class fails {@code NutWiringContractTest} while {@code /v1/swap} is still advertised.
 */
@Slf4j
final class SwapResponseReplay {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SwapRequestFingerprint fingerprint;
    private final SwapResponseCache cache;

    private SwapResponseReplay(SwapRequestFingerprint fingerprint, SwapResponseCache cache) {
        this.fingerprint = fingerprint;
        this.cache = cache;
    }

    static <T extends Secret> SwapResponseReplay of(@NonNull PostSwapRequest<T> request,
                                                    @NonNull SwapResponseCache cache) {
        return new SwapResponseReplay(SwapRequestFingerprint.of(request), cache);
    }

    /**
     * The response this exact request already received, if it succeeded within the ttl.
     *
     * @throws CashuErrorException {@code internal_error} when a stored response cannot be read
     *                             back, which is a defect rather than a miss: processing the
     *                             request again would only fail on its spent inputs
     */
    Optional<PostSwapResponse> previousResponse() throws CashuErrorException {
        Optional<CachedSwapResponse> cached = lookUp();
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        log.info("[swap][nut19] replay request_fingerprint={}", fingerprint);
        return Optional.of(decode(cached.get().responseJson()));
    }

    /**
     * Remembers a successful response so a replay of this request gets it back.
     *
     * @param response the response about to be sent to the wallet
     */
    void remember(@NonNull PostSwapResponse response) {
        try {
            cache.store(fingerprint, encode(response));
        } catch (RuntimeException storeFailure) {
            log.error("[swap][nut19][alert] cache_store_failed request_fingerprint={} — a replay of "
                            + "this swap will be refused; its outputs remain recoverable through "
                            + "NUT-09 restore",
                    fingerprint, storeFailure);
        }
    }

    private Optional<CachedSwapResponse> lookUp() {
        try {
            return cache.find(fingerprint);
        } catch (RuntimeException lookupFailure) {
            log.warn("[swap][nut19] cache_lookup_failed request_fingerprint={} cause={} — "
                    + "processing the request as new", fingerprint, lookupFailure.getMessage());
            return Optional.empty();
        }
    }

    private static String encode(PostSwapResponse response) {
        try {
            return JSON.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise PostSwapResponse", e);
        }
    }

    private PostSwapResponse decode(String json) throws CashuErrorException {
        try {
            return JSON.readValue(json, PostSwapResponse.class);
        } catch (JsonProcessingException e) {
            log.error("[swap][nut19][alert] cached_response_unreadable request_fingerprint={}",
                    fingerprint, e);
            throw new CashuErrorException(CashuErrorCode.internal_error);
        }
    }
}
