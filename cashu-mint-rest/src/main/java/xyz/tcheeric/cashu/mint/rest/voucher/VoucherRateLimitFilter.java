package xyz.tcheeric.cashu.mint.rest.voucher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.rest.config.VoucherDurabilityProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spec 003 FR-008 / T311 — per-principal token-bucket rate limit on the
 * voucher endpoints. Implementation is in-process and Caffeine-backed
 * (research R4); migration to Redis is contained to this class if the
 * mint ever goes multi-replica.
 *
 * <p>Refill is computed lazily: each request decrements a counter, and
 * a sliding window-second mark refreshes the bucket. Bucket capacity =
 * {@code rateLimitTokensPerMinute}; refill period is one minute.
 *
 * <p>On exhaustion the filter writes a 429 with a {@code Retry-After}
 * header (seconds) and a {@code X-RateLimit-Remaining: 0} signal. The
 * Micrometer counter {@code cashu_mint_voucher_rate_limit_breach_total}
 * fires for operator alerting (FR-014).
 */
@Slf4j
@Component
public class VoucherRateLimitFilter extends OncePerRequestFilter {

    private static final String VOUCHER_PATH_PREFIX = "/v1/vouchers";
    private static final String HEADER_RATELIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final VoucherDurabilityProperties properties;

    public VoucherRateLimitFilter(VoucherDurabilityProperties properties) {
        this.properties = properties;
    }

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(VOUCHER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String principalId = resolvePrincipal();
        if (principalId == null) {
            // Auth has already rejected the request via SecurityFilterChain;
            // this is a defensive belt-and-braces no-op.
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.get(principalId, key -> new Bucket(properties.getRateLimitTokensPerMinute()));
        long remaining = bucket.tryConsume(System.currentTimeMillis(), properties.getRateLimitTokensPerMinute());
        response.setHeader(HEADER_RATELIMIT_REMAINING, Long.toString(Math.max(0, remaining)));

        if (remaining < 0) {
            response.setHeader(HEADER_RETRY_AFTER, "60");
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            log.warn("voucher_rate_limit principal={} remaining=0 path={}", principalId, request.getRequestURI());
            // The principal is logged, not labelled — see
            // VoucherMetricsRecorder for why it must not become a series
            // dimension (cardinality + spec-004 data minimisation).
            MetricRecorders.voucher().rateLimitBreach();
            return;
        }

        chain.doFilter(request, response);
    }

    private static String resolvePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    /** Token bucket per principal. {@code remaining} is the count of available tokens. */
    private static final class Bucket {
        private final AtomicLong remaining;
        private volatile long windowStartMs;

        Bucket(int capacity) {
            this.remaining = new AtomicLong(capacity);
            this.windowStartMs = System.currentTimeMillis();
        }

        /**
         * Atomically decrements the bucket; returns the post-consumption count
         * (negative when the request should be rejected).
         */
        long tryConsume(long nowMs, int capacity) {
            if (nowMs - windowStartMs >= 60_000L) {
                synchronized (this) {
                    if (nowMs - windowStartMs >= 60_000L) {
                        windowStartMs = nowMs;
                        remaining.set(capacity);
                    }
                }
            }
            return remaining.decrementAndGet();
        }
    }
}
