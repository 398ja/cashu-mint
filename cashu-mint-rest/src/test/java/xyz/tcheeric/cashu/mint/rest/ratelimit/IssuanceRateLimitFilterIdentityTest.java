package xyz.tcheeric.cashu.mint.rest.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import xyz.tcheeric.cashu.mint.rest.config.IssuanceRateLimitProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AppSec finding M-2 (issue #425): the issuance rate limit must not be escapable by rotating a
 * client-supplied header.
 *
 * <p>The header used to select the bucket outright, with the remote address as a fallback only
 * when it was absent. A caller who could reach {@code /v1/mint} directly therefore sent a fresh
 * header per request, got a fresh quota each time, and never touched the fallback.
 *
 * <p>These tests pin the property that closes it: the remote address is always part of the key, so
 * a rotated header can only subdivide one address's quota, never mint new ones.
 */
class IssuanceRateLimitFilterIdentityTest {

    private static final String IDENTITY_HEADER = "X-Dalia-Identity";
    private static final int BURST = 3;

    private IssuanceRateLimitProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IssuanceRateLimitProperties();
        properties.setEnabled(true);
        properties.setPerMinuteBurst(BURST);
        properties.setPerDay(1000);
        properties.setIdentityHeader(IDENTITY_HEADER);
    }

    /**
     * The regression test for the bypass: an untrusted caller rotating the identity header every
     * request must still be cut off at the burst limit, because the bucket is keyed on its address.
     */
    @Test
    void rotatingTheIdentityHeaderCannotEscapeTheLimit() throws Exception {
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        int allowed = 0;
        for (int i = 0; i < BURST * 5; i++) {
            MockHttpServletResponse response = requestFrom(filter, "203.0.113.7", "rotating-" + i);
            if (response.getStatus() != 429) {
                allowed++;
            }
        }

        assertThat(allowed)
                .as("a rotated header must not buy more than one address's quota")
                .isEqualTo(BURST);
    }

    /** The same caller without any header is bounded identically — the baseline behaviour. */
    @Test
    void aCallerWithNoHeaderIsBoundedByItsAddress() throws Exception {
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        int allowed = 0;
        for (int i = 0; i < BURST * 5; i++) {
            MockHttpServletResponse response = requestFrom(filter, "203.0.113.8", null);
            if (response.getStatus() != 429) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(BURST);
    }

    /**
     * Distinct addresses keep distinct quotas, so the fix does not collapse every caller into one
     * shared bucket. Without this, a filter that simply refused everything would pass the tests
     * above.
     */
    @Test
    void distinctAddressesKeepDistinctQuotas() throws Exception {
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        for (int i = 0; i < BURST; i++) {
            assertThat(requestFrom(filter, "203.0.113.9", null).getStatus()).isNotEqualTo(429);
        }
        assertThat(requestFrom(filter, "203.0.113.9", null).getStatus()).isEqualTo(429);

        assertThat(requestFrom(filter, "203.0.113.10", null).getStatus())
                .as("a different source address must not inherit an exhausted bucket")
                .isNotEqualTo(429);
    }

    /**
     * A trusted proxy's header still subdivides its address, which is the legitimate use: the
     * engine fronts many callers from one address and wants them limited separately.
     */
    @Test
    void aTrustedProxyHeaderSubdividesThatAddressesQuota() throws Exception {
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        for (int i = 0; i < BURST; i++) {
            assertThat(requestFrom(filter, "10.1.2.3", "merchant-a").getStatus()).isNotEqualTo(429);
        }
        assertThat(requestFrom(filter, "10.1.2.3", "merchant-a").getStatus()).isEqualTo(429);

        assertThat(requestFrom(filter, "10.1.2.3", "merchant-b").getStatus())
                .as("a second identity behind a trusted proxy has its own bucket")
                .isNotEqualTo(429);
    }

    /**
     * The allowlist is what separates the two behaviours above: the same header from an untrusted
     * address must be ignored, or the trusted path would simply reinstate the bypass.
     */
    @Test
    void theSameHeaderFromAnUntrustedAddressIsIgnored() throws Exception {
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        for (int i = 0; i < BURST; i++) {
            assertThat(requestFrom(filter, "198.51.100.4", "merchant-a").getStatus()).isNotEqualTo(429);
        }
        assertThat(requestFrom(filter, "198.51.100.4", "merchant-b").getStatus())
                .as("an untrusted peer's header must not open a second bucket")
                .isEqualTo(429);
    }

    /** A malformed allowlist entry is dropped rather than failing startup, and trusts nothing. */
    @Test
    void aMalformedTrustedProxyEntryIsIgnoredRatherThanTrusted() throws Exception {
        properties.setTrustedProxies(List.of("not-an-address"));
        IssuanceRateLimitFilter filter = new IssuanceRateLimitFilter(properties);

        for (int i = 0; i < BURST; i++) {
            assertThat(requestFrom(filter, "203.0.113.11", "x-" + i).getStatus()).isNotEqualTo(429);
        }
        assertThat(requestFrom(filter, "203.0.113.11", "x-final").getStatus())
                .as("a malformed entry must trust less, not more")
                .isEqualTo(429);
    }

    private static MockHttpServletResponse requestFrom(IssuanceRateLimitFilter filter,
                                                       String remoteAddress,
                                                       String identity) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mint/bolt11");
        request.setRemoteAddr(remoteAddress);
        if (identity != null) {
            request.addHeader(IDENTITY_HEADER, identity);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }
}
