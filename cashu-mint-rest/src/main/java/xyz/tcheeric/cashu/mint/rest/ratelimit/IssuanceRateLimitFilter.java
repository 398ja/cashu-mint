package xyz.tcheeric.cashu.mint.rest.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.tcheeric.cashu.mint.rest.config.IssuanceRateLimitProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dalia Phase 9 — per-identity rate limit on mint issuance ({@code /v1/mint/**}). In-process and
 * Caffeine-backed (migration to Redis is contained to this class if the mint goes multi-replica).
 *
 * <p>Each identity gets a dual token bucket: a per-minute burst and a per-day quota. Identity is the
 * engine-supplied identity header when present, else the caller's remote address (NUT endpoints carry
 * no authenticated principal). On exhaustion the filter writes a 429 with {@code Retry-After} and a
 * JSON body, and fires the Micrometer counter {@code cashu_mint_issuance_rate_limit_breach_total}.
 *
 * <p><b>Security / deployment note.</b> The identity header is <em>client-supplied</em> and therefore
 * spoofable: a caller who can reach {@code /v1/mint} directly can rotate the header to mint a fresh
 * bucket per request and bypass the limit (the remote-address fallback only applies when the header is
 * absent). This is acceptable <em>only</em> when {@code /v1/mint} is not exposed to untrusted clients —
 * in the Dalia pilot the authenticated engine is the sole caller and sets the header. Do NOT rely on
 * this limit alone if the endpoint is reachable by untrusted callers; front it with real auth
 * (e.g. NUT-22 blind auth) or a network boundary.
 */
@Slf4j
@Component
public class IssuanceRateLimitFilter extends OncePerRequestFilter {

    private static final String MINT_PATH_PREFIX = "/v1/mint";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String COUNTER_BREACH = "cashu_mint_issuance_rate_limit_breach_total";
    /** Cap on the client-supplied identity used as a cache key + log field (bounds memory + log-injection surface). */
    private static final int MAX_IDENTITY_LEN = 128;

    private final IssuanceRateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    private final Cache<String, DualBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(2))
            .maximumSize(200_000)
            .build();

    public IssuanceRateLimitFilter(IssuanceRateLimitProperties properties,
                                   @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith(MINT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String identity = resolveIdentity(request);
        DualBucket bucket = buckets.get(identity, key -> new DualBucket());
        boolean allowed = bucket.tryConsume(
                System.currentTimeMillis(), properties.getPerMinuteBurst(), properties.getPerDay());

        if (!allowed) {
            response.setHeader(HEADER_RETRY_AFTER, "60");
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            log.warn("issuance_rate_limit identity={} path={}", identity, request.getRequestURI());
            if (meterRegistry != null) {
                meterRegistry.counter(COUNTER_BREACH).increment();
            }
            return;
        }

        chain.doFilter(request, response);
    }

    /** Identity = the engine-supplied header if present, else the remote address (namespaced). */
    private String resolveIdentity(HttpServletRequest request) {
        String header = request.getHeader(properties.getIdentityHeader());
        if (header != null && !header.isBlank()) {
            return "id:" + sanitizeIdentity(header);
        }
        String remote = request.getRemoteAddr();
        return "ip:" + (remote != null ? remote : "unknown");
    }

    /**
     * Bound + sanitize a client-controlled identity before it is used as a cache key and log field: strip
     * control characters (CR/LF ⇒ log-injection defense) and cap the length (bounds per-identity memory).
     */
    private static String sanitizeIdentity(String raw) {
        String cleaned = raw.replaceAll("\\p{Cntrl}", "");
        return cleaned.length() > MAX_IDENTITY_LEN ? cleaned.substring(0, MAX_IDENTITY_LEN) : cleaned;
    }

    /** A per-identity token bucket with two windows: a per-minute burst and a per-day quota. */
    private static final class DualBucket {
        private final AtomicLong minuteRemaining = new AtomicLong();
        private final AtomicLong dayRemaining = new AtomicLong();
        private long minuteStartMs;
        private long dayStartMs;
        private boolean initialized;

        synchronized boolean tryConsume(long nowMs, int burst, int perDay) {
            if (!initialized) {
                minuteStartMs = nowMs;
                dayStartMs = nowMs;
                minuteRemaining.set(burst);
                dayRemaining.set(perDay);
                initialized = true;
            }
            if (nowMs - minuteStartMs >= 60_000L) {
                minuteStartMs = nowMs;
                minuteRemaining.set(burst);
            }
            if (nowMs - dayStartMs >= 86_400_000L) {
                dayStartMs = nowMs;
                dayRemaining.set(perDay);
            }
            if (minuteRemaining.get() <= 0 || dayRemaining.get() <= 0) {
                return false;
            }
            minuteRemaining.decrementAndGet();
            dayRemaining.decrementAndGet();
            return true;
        }
    }
}
