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
 * Per-identity rate limit on mint issuance ({@code /v1/mint/**}). In-process and
 * Caffeine-backed (migration to Redis is contained to this class if the mint goes multi-replica).
 *
 * <p>Originally written for Dalia Phase 9, which is where the old {@code X-Dalia-Identity} header
 * name came from. The mint is not a Dalia component and Dalia is not deployed alongside it, so the
 * header is {@code X-Mint-Issuer-Identity}.
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
    /** Forwarded client address set by a single reverse proxy. Believed only from a trusted peer. */
    private static final String REAL_IP_HEADER = "X-Real-IP";

    /** Forwarded client chain; the first entry is the original client. Believed only from a trusted peer. */
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

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
        if (!properties.isEnabled() || !request.getRequestURI().startsWith(MINT_PATH_PREFIX)) {
            return true;
        }
        // Read-only quote status polls are NOT issuance and must not spend the issuance budget.
        //
        // NUT-04 has the wallet poll GET /v1/mint/quote/{method}/{quote_id} until the quote
        // reports PAID. Counting those against the same bucket as minting makes the limit
        // self-defeating: one voucher costs a single POST and then a poll every ~2s, so a
        // perMinuteBurst of 10 is exhausted by ONE issuance waiting for its invoice. Observed on
        // staging as 15 rejections on a single quote id, which stalled the voucher at "waiting to
        // be backed" AFTER the mint had already issued it, and tripped the wallet's circuit
        // breaker on top.
        //
        // The limit exists to cap how much a caller can MINT. A GET creates nothing, so
        // exempting it gives up no protection that matters. Writes under /v1/mint are still
        // limited exactly as before.
        return "GET".equalsIgnoreCase(request.getMethod());
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
        String peer = request.getRemoteAddr();
        // The address that identifies the CALLER, which is not always the peer.
        //
        // When a reverse proxy terminates TLS and forwards to the mint, every request arrives
        // from the proxy. On staging that is a host nginx proxying to localhost:7777, so the
        // peer is the docker bridge gateway (172.19.0.1) for EVERY caller — internal services
        // and the public internet alike. One bucket for the world: 10 requests a minute shared
        // by every merchant, every wallet, and any stranger who found the mint. Merchant
        // voucher issuance died on 429 while the limiter looked correctly configured.
        //
        // X-Real-IP is only believed from an allowlisted peer, which is the same trust
        // boundary the identity header already uses. From anyone else it is ignored, so a
        // caller who can reach the mint directly cannot mint a fresh bucket per request by
        // spoofing it.
        String remote = resolveCallerAddress(request, peer);
        String addressKey = "ip:" + (remote != null ? remote : "unknown");

        if (!isTrustedPeer(peer)) {
            return addressKey;
        }
        String header = request.getHeader(properties.getIdentityHeader());
        if (header == null || header.isBlank()) {
            return addressKey;
        }
        return addressKey + "|id:" + sanitizeIdentity(header);
    }

    /**
     * The caller's address: the forwarded client address when the peer is trusted, else the peer.
     *
     * <p>Only the FIRST entry of {@code X-Forwarded-For} is the original client; the rest are
     * appended by intermediaries. {@code X-Real-IP} is preferred because a single proxy sets it
     * to exactly that value. Both are ignored entirely from an untrusted peer.
     */
    private String resolveCallerAddress(HttpServletRequest request, String peer) {
        if (!isTrustedPeer(peer)) {
            return peer;
        }
        String realIp = request.getHeader(REAL_IP_HEADER);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return peer;
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
