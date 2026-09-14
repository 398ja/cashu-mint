package xyz.tcheeric.cashu.mint.rest.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.rest.config.IssuanceRateLimitProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dalia Phase 9 — per-identity rate limit on mint issuance ({@code /v1/mint/**}). In-process and
 * Caffeine-backed (migration to Redis is contained to this class if the mint goes multi-replica).
 *
 * <p>Each identity gets a dual token bucket: a per-minute burst and a per-day quota. Identity is the
 * caller's remote address, subdivided by the engine-supplied identity header when that header arrives
 * from a trusted peer (NUT endpoints carry no authenticated principal). On exhaustion the filter
 * writes a 429 with {@code Retry-After} and a JSON body, and fires the Micrometer counter
 * {@code cashu_mint_issuance_rate_limit_breach_total}.
 *
 * <h2>Why identity is anchored to the remote address (AppSec finding M-2, issue #425)</h2>
 *
 * <p>The identity header is client-supplied. It used to select the bucket on its own, with the remote
 * address as a fallback only when the header was absent — so a caller who could reach
 * {@code /v1/mint} directly rotated the header per request, minted a fresh quota every time, and
 * never touched the fallback. The limit was worth exactly as much as the network boundary in front
 * of it, and nothing in the code required that boundary to exist.
 *
 * <p>Now the bucket key always begins with the remote address, and the header only refines it when
 * the peer is listed in {@code cashu.mint.issuance.rate-limit.trusted-proxies}. From any other
 * caller the header is ignored outright, so rotating it changes nothing: every request from one
 * address shares one bucket. The allowlist is empty by default, which means a mint that has not
 * configured it is limited purely by address.
 *
 * <p>What trusting a peer costs is worth stating plainly, because it is easy to overstate the
 * guarantee. Behind a trusted proxy each identity gets its <em>own</em> full-sized bucket, so N
 * identities admit N times the burst. That is the point of the feature -- a proxy reporting real
 * per-user identity should not have its users throttled as one -- but it means the allowlist is the
 * operator asserting those headers are trustworthy. An allowlist entry reachable by untrusted
 * callers hands them the same multiplication the fix removed.
 * {@code trustedPeerGetsAnIndependentBucketPerIdentity} pins that behaviour so it stays a
 * deliberate trade rather than a surprise.
 *
 * <p>This is still not authentication. A distributed caller has as many buckets as it has source
 * addresses, so for a genuinely public {@code /v1/mint} this should be fronted with real auth
 * (e.g. NUT-22 blind auth) or a network boundary. What changed is that the control no longer has a
 * single-header bypass.
 */
@Slf4j
@Component
public class IssuanceRateLimitFilter extends OncePerRequestFilter {

    private static final String MINT_PATH_PREFIX = "/v1/mint";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    /** Cap on the client-supplied identity used as a cache key + log field (bounds memory + log-injection surface). */
    private static final int MAX_IDENTITY_LEN = 128;

    private final IssuanceRateLimitProperties properties;

    /** Parsed once at construction: matching a CIDR per request should not re-parse the config. */
    private final List<IpAddressMatcher> trustedProxyMatchers;

    private final Cache<String, DualBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(2))
            .maximumSize(200_000)
            .build();

    public IssuanceRateLimitFilter(IssuanceRateLimitProperties properties) {
        this.properties = properties;
        this.trustedProxyMatchers = properties.getTrustedProxies().stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(IssuanceRateLimitFilter::matcherOrNull)
                .filter(Objects::nonNull)
                .toList();
        if (trustedProxyMatchers.isEmpty() && properties.isEnabled()) {
            log.info("issuance_rate_limit trusted_proxies_unset — the '{}' header will be ignored "
                            + "and buckets keyed on the remote address alone. Set "
                            + "cashu.mint.issuance.rate-limit.trusted-proxies if a proxy or engine "
                            + "supplies caller identity.",
                    properties.getIdentityHeader());
        }
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
            MetricRecorders.issuance().rateLimitBreach();
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Identity = the remote address, subdivided by the supplied header when the peer is trusted.
     *
     * <p>The address is always part of the key. That is the whole fix: a rotated header can only
     * carve one address's quota into smaller shares, never mint new ones.
     */
    private String resolveIdentity(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String addressKey = "ip:" + (remote != null ? remote : "unknown");

        if (!isTrustedPeer(remote)) {
            return addressKey;
        }
        String header = request.getHeader(properties.getIdentityHeader());
        if (header == null || header.isBlank()) {
            return addressKey;
        }
        return addressKey + "|id:" + sanitizeIdentity(header);
    }

    /** Whether the identity header from this peer is believed. Empty allowlist trusts nobody. */
    private boolean isTrustedPeer(String remoteAddress) {
        if (remoteAddress == null || trustedProxyMatchers.isEmpty()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxyMatchers) {
            if (matcher.matches(remoteAddress)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A matcher for one allowlist entry, or {@code null} if it cannot be parsed.
     *
     * <p>A malformed entry is dropped with a warning rather than failing startup: refusing to boot
     * the mint over a rate-limit config typo would trade a small control for total unavailability.
     * Dropping it is safe in the direction that matters — an unparsed entry trusts less, not more.
     */
    private static IpAddressMatcher matcherOrNull(String entry) {
        try {
            return new IpAddressMatcher(entry);
        } catch (IllegalArgumentException malformed) {
            log.warn("issuance_rate_limit ignoring_malformed_trusted_proxy entry={} reason={}",
                    entry, malformed.getMessage());
            return null;
        }
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
