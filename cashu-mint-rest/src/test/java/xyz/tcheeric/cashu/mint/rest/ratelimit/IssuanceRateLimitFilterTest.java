package xyz.tcheeric.cashu.mint.rest.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.rest.config.IssuanceRateLimitProperties;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dalia Phase 9 — per-identity issuance rate limit: burst, daily cap, identity isolation, keying. */
class IssuanceRateLimitFilterTest {

    /** Exposes the protected filter method so we test the limiter logic without the servlet machinery. */
    private static final class TestableFilter extends IssuanceRateLimitFilter {
        TestableFilter(IssuanceRateLimitProperties p) {
            super(p);
        }

        void run(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws Exception {
            doFilterInternal(req, resp, chain);
        }

        boolean skips(HttpServletRequest req) {
            return shouldNotFilter(req);
        }
    }

    private IssuanceRateLimitProperties props(int burst, int perDay) {
        IssuanceRateLimitProperties p = new IssuanceRateLimitProperties();
        p.setPerMinuteBurst(burst);
        p.setPerDay(perDay);
        return p;
    }

    /**
     * Properties that trust the identity header from the loopback-ish test addresses.
     *
     * <p>Needed since AppSec finding M-2 (issue #425): the header no longer selects a bucket on
     * its own, because that let any caller rotate it and mint unlimited quota. It now only
     * subdivides the remote address's bucket, and only from a peer on the trusted-proxy
     * allowlist. Tests about per-identity isolation therefore have to say which peer is trusted.
     */
    private IssuanceRateLimitProperties trustingProps(int burst, int perDay) {
        IssuanceRateLimitProperties p = props(burst, perDay);
        p.setTrustedProxies(List.of("0.0.0.0/0"));
        return p;
    }

    private HttpServletRequest request(String header, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/mint/bolt11");
        when(req.getHeader("X-Dalia-Identity")).thenReturn(header);
        when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }

    /** A request with an explicit HTTP method, for the read-vs-write exemption. */
    private HttpServletRequest methodRequest(String method, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/mint/quote/bolt11/abc");
        when(req.getMethod()).thenReturn(method);
        when(req.getHeader("X-Dalia-Identity")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }

    /** A request arriving via a reverse proxy: same peer for everyone, distinct forwarded client. */
    private HttpServletRequest forwardedRequest(String peer, String realIp) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/mint/bolt11");
        when(req.getHeader("X-Dalia-Identity")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn(realIp);
        when(req.getRemoteAddr()).thenReturn(peer);
        return req;
    }

    private HttpServletResponse response() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    void burstRefusedAfterLimit() throws Exception {
        // The request past the per-minute burst (=3) for one identity is refused with 429.
        TestableFilter filter = new TestableFilter(props(3, 100));
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 3; i++) {
            filter.run(request("alice", "1.1.1.1"), response(), chain);
        }
        HttpServletResponse blocked = response();
        filter.run(request("alice", "1.1.1.1"), blocked, chain);
        verify(chain, times(3)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void dailyCapRefusedEvenBelowBurst() throws Exception {
        // With a daily cap (=2) below the burst, the daily quota is what bites.
        TestableFilter filter = new TestableFilter(props(10, 2));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("bob", "2.2.2.2"), response(), chain);
        filter.run(request("bob", "2.2.2.2"), response(), chain);
        HttpServletResponse blocked = response();
        filter.run(request("bob", "2.2.2.2"), blocked, chain);
        verify(chain, times(2)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void secondIdentityUnaffected() throws Exception {
        // One identity hitting its cap does not throttle a different identity behind the same
        // trusted proxy. The proxy must be trusted for the header to be honoured at all (#425):
        // from an untrusted peer both identities share the address bucket, which is the point.
        TestableFilter filter = new TestableFilter(trustingProps(1, 100));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("alice", "1.1.1.1"), response(), chain); // alice #1 allowed
        filter.run(request("alice", "1.1.1.1"), response(), chain); // alice #2 blocked
        filter.run(request("carol", "1.1.1.1"), response(), chain); // carol #1 allowed
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void identityHeaderControlCharsStripped() throws Exception {
        // Control chars in the identity header are stripped, so an injected variant maps to the SAME bucket
        // (defends against CR/LF log injection and bucket-bypass via cosmetic header variation).
        TestableFilter filter = new TestableFilter(trustingProps(2, 100));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("dave", "9.9.9.9"), response(), chain);   // dave #1 allowed
        filter.run(request("dave", "9.9.9.9"), response(), chain);   // dave #2 allowed (burst = 2)
        HttpServletResponse blocked = response();
        filter.run(request("da\r\nve", "9.9.9.9"), blocked, chain);  // sanitizes to "dave" → same bucket → refused
        verify(chain, times(2)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void keyedByRemoteAddrWhenNoHeader() throws Exception {
        // Absent the identity header, the bucket is keyed by remote address.
        TestableFilter filter = new TestableFilter(props(1, 100));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request(null, "9.9.9.9"), response(), chain);   // 9.9.9.9 #1 allowed
        HttpServletResponse blocked = response();
        filter.run(request(null, "9.9.9.9"), blocked, chain);      // 9.9.9.9 #2 blocked
        filter.run(request(null, "8.8.8.8"), response(), chain);   // different ip allowed
        verify(chain, times(2)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void identityHeaderIgnoredWhenNoProxyIsTrusted() throws Exception {
        // With no trusted-proxy allowlist configured -- the default -- the identity header must
        // carry no weight at all. This is the state a fresh deployment starts in, so if the
        // header were believed here, rotating it would mint unlimited quota on any mint that had
        // not yet configured the allowlist: finding M-2 (issue #425) in its original form.
        TestableFilter filter = new TestableFilter(props(1, 100));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("alice", "9.9.9.9"), response(), chain);
        HttpServletResponse blocked = response();
        filter.run(request("bob", "9.9.9.9"), blocked, chain);
        // Same address, different header: still one bucket, so the second call is refused.
        verify(chain, times(1)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void identityHeaderIgnoredFromAnUntrustedPeer() throws Exception {
        // An allowlist that does not cover the caller must be treated as no trust for that
        // caller, rather than falling back to believing the header.
        IssuanceRateLimitProperties properties = props(1, 100);
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        TestableFilter filter = new TestableFilter(properties);
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("alice", "9.9.9.9"), response(), chain);
        HttpServletResponse blocked = response();
        filter.run(request("bob", "9.9.9.9"), blocked, chain);
        verify(chain, times(1)).doFilter(any(), any());
        verify(blocked).setStatus(429);
    }

    @Test
    void trustedPeerGetsAnIndependentBucketPerIdentity() throws Exception {
        // Pins the cost of trusting a peer, which is easy to misread as a stronger guarantee than
        // it is. Behind a trusted proxy each identity gets its own full-sized bucket, so four
        // identities against a burst of 1 produce four admissions. That is the intended feature --
        // it is what a proxy that reports real per-user identity is for -- but it means the
        // allowlist is a statement that the operator vouches for those headers. List an address
        // that can be reached by untrusted callers and the limit is effectively gone for them,
        // which is why the allowlist is empty by default.
        TestableFilter filter = new TestableFilter(trustingProps(1, 100));
        FilterChain chain = mock(FilterChain.class);
        filter.run(request("id-1", "7.7.7.7"), response(), chain);
        filter.run(request("id-2", "7.7.7.7"), response(), chain);
        filter.run(request("id-3", "7.7.7.7"), response(), chain);
        filter.run(request("id-4", "7.7.7.7"), response(), chain);
        verify(chain, times(4)).doFilter(any(), any());
    }

    /**
     * Two callers behind one reverse proxy must not share a bucket.
     *
     * <p>This is the staging outage: a host nginx proxied to the mint, so every request — from
     * internal services and from the public internet alike — arrived from the docker bridge
     * gateway. All of them keyed to one identity, so 10 requests a minute was the budget for the
     * ENTIRE platform, and merchant voucher issuance died on 429 while the limiter looked
     * correctly configured.
     *
     * <p>Without the X-Real-IP resolution this fails on the second caller's first request.
     */
    @Test
    void callersBehindOneTrustedProxyGetIndependentBuckets() throws Exception {
        TestableFilter filter = new TestableFilter(trustingProps(1, 10));
        FilterChain chain = mock(FilterChain.class);

        filter.run(forwardedRequest("172.19.0.1", "203.0.113.7"), response(), chain);

        HttpServletResponse second = response();
        filter.run(forwardedRequest("172.19.0.1", "203.0.113.8"), second, chain);

        // The second caller is a different client, so its own burst is still intact.
        verify(second, never()).setStatus(429);
    }

    /**
     * The same forwarded client is still limited: this refines the key, it does not remove it.
     */
    @Test
    void oneForwardedClientIsStillRateLimited() throws Exception {
        TestableFilter filter = new TestableFilter(trustingProps(1, 10));
        FilterChain chain = mock(FilterChain.class);

        filter.run(forwardedRequest("172.19.0.1", "203.0.113.7"), response(), chain);

        HttpServletResponse second = response();
        filter.run(forwardedRequest("172.19.0.1", "203.0.113.7"), second, chain);

        verify(second).setStatus(429);
    }

    /**
     * X-Real-IP from an UNTRUSTED peer is ignored, or the header becomes a limit bypass: a
     * caller reaching the mint directly would rotate it and mint a fresh bucket per request.
     * Same trust boundary the identity header already uses (M-2).
     */
    @Test
    void forwardedAddressIgnoredFromAnUntrustedPeer() throws Exception {
        TestableFilter filter = new TestableFilter(props(1, 10));
        FilterChain chain = mock(FilterChain.class);

        filter.run(forwardedRequest("198.51.100.4", "203.0.113.7"), response(), chain);

        HttpServletResponse second = response();
        filter.run(forwardedRequest("198.51.100.4", "203.0.113.9"), second, chain);

        // Rotating the header bought nothing: both keyed to the untrusted peer's own address.
        verify(second).setStatus(429);
    }

    /**
     * NUT-04 quote status polling must not spend the issuance budget.
     *
     * <p>The wallet polls GET /v1/mint/quote/{method}/{quote_id} every couple of seconds until the
     * quote is PAID. Counting those as issuance made ONE voucher exhaust a burst of 10 while it
     * waited for its own invoice — observed on staging as 15 rejections against a single quote id,
     * stalling the voucher at "waiting to be backed" after the mint had already issued it.
     */
    @Test
    void quoteStatusPollsAreNotRateLimited() {
        TestableFilter filter = new TestableFilter(props(1, 10));

        // Far beyond the burst of 1; none of these should even reach the bucket.
        for (int i = 0; i < 20; i++) {
            assertTrue(filter.skips(methodRequest("GET", "1.2.3.4")),
                    "a read-only quote status poll must be exempt");
        }
    }

    /** Writes under /v1/mint are still limited: the exemption is for reads only. */
    @Test
    void mintWritesAreStillRateLimited() {
        TestableFilter filter = new TestableFilter(props(1, 10));
        assertFalse(filter.skips(methodRequest("POST", "1.2.3.4")),
                "minting must stay inside the limiter");
    }
}
