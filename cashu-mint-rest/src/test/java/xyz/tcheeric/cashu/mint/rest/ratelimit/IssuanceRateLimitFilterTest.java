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
import static org.mockito.Mockito.mock;
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
}
