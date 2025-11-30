package xyz.tcheeric.cashu.mint.observability.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricsHandlerInterceptor}.
 *
 * Verifies that HTTP request metrics are correctly recorded.
 */
@ExtendWith(MockitoExtension.class)
class MetricsHandlerInterceptorTest {

    private SimpleMeterRegistry registry;
    private MetricsHandlerInterceptor interceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Object handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new MetricsHandlerInterceptor(registry);
    }

    @Test
    void preHandle_setsStartTime() {
        // When preHandle is called
        boolean result = interceptor.preHandle(request, response, handler);

        // Then it returns true (allow request to proceed)
        assertThat(result).isTrue();

        // And start time is set
        verify(request).setAttribute(eq("cashu.metrics.startTime"), anyLong());
    }

    @Test
    void afterCompletion_recordsSuccessfulRequest() {
        // Given a successful request
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(System.nanoTime() - 50_000_000L);
        when(request.getRequestURI()).thenReturn("/v1/swap");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(200);

        // When afterCompletion is called
        interceptor.afterCompletion(request, response, handler, null);

        // Then duration timer is recorded
        Timer timer = registry.find("cashu_mint_requests_duration_seconds")
                .tag("endpoint", "/v1/swap")
                .tag("method", "POST")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        // And request counter is incremented
        Counter counter = registry.find("cashu_mint_requests_total")
                .tag("endpoint", "/v1/swap")
                .tag("method", "POST")
                .tag("status", "2xx")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void afterCompletion_recordsClientError() {
        // Given a client error response
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(System.nanoTime() - 10_000_000L);
        when(request.getRequestURI()).thenReturn("/v1/mint/bolt11");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(400);

        // When afterCompletion is called
        interceptor.afterCompletion(request, response, handler, null);

        // Then request counter shows 4xx
        Counter counter = registry.find("cashu_mint_requests_total")
                .tag("endpoint", "/v1/mint/bolt11")
                .tag("status", "4xx")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And error counter is incremented
        Counter errorCounter = registry.find("cashu_mint_requests_errors_total")
                .tag("endpoint", "/v1/mint/bolt11")
                .tag("error_type", "http_400")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);
    }

    @Test
    void afterCompletion_recordsServerError() {
        // Given a server error response
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(System.nanoTime() - 10_000_000L);
        when(request.getRequestURI()).thenReturn("/v1/melt/bolt11");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(500);

        // When afterCompletion is called
        interceptor.afterCompletion(request, response, handler, null);

        // Then request counter shows 5xx
        Counter counter = registry.find("cashu_mint_requests_total")
                .tag("status", "5xx")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void afterCompletion_recordsException() {
        // Given a request that threw an exception
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(System.nanoTime() - 10_000_000L);
        when(request.getRequestURI()).thenReturn("/v1/swap");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(500);
        RuntimeException ex = new RuntimeException("Test error");

        // When afterCompletion is called with exception
        interceptor.afterCompletion(request, response, handler, ex);

        // Then error counter uses exception class name
        Counter errorCounter = registry.find("cashu_mint_requests_errors_total")
                .tag("endpoint", "/v1/swap")
                .tag("error_type", "RuntimeException")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);
    }

    @Test
    void afterCompletion_skipsIfNoStartTime() {
        // Given no start time was set (preHandle not called)
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(null);

        // When afterCompletion is called
        interceptor.afterCompletion(request, response, handler, null);

        // Then no metrics are recorded
        assertThat(registry.find("cashu_mint_requests_duration_seconds").timer()).isNull();
        assertThat(registry.find("cashu_mint_requests_total").counter()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "/v1/keys/abc123, /v1/keys/{keyset_id}",
            "/v1/keys/keyset/abc123, /v1/keys/keyset/{keyset_id}",
            "/v1/mint/quote/bolt11/uuid-123-456, /v1/mint/quote/{method}/{quote_id}",
            "/v1/melt/quote/bolt11/uuid-789, /v1/melt/quote/{method}/{quote_id}",
            "/v1/mint/quote/voucher/bolt11/uuid-abc, /v1/mint/quote/voucher/{method}/{quote_id}",
            "/v1/swap, /v1/swap",
            "/v1/mint/bolt11, /v1/mint/bolt11",
            "/v1/melt/bolt11, /v1/melt/bolt11",
            "/v1/keysets, /v1/keysets",
            "/v1/info, /v1/info",
            "/v1/checkstate, /v1/checkstate",
            "/v1/restore, /v1/restore"
    })
    void normalizeEndpoint_normalizesVariablePaths(String input, String expected) {
        // When normalizing endpoint
        String result = interceptor.normalizeEndpoint(input);

        // Then it matches expected pattern
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void normalizeEndpoint_handlesNullAndEmpty() {
        // When normalizing null or empty
        assertThat(interceptor.normalizeEndpoint(null)).isEqualTo("/");
        assertThat(interceptor.normalizeEndpoint("")).isEqualTo("/");
    }

    @Test
    void multipleRequests_aggregatesMetrics() {
        // Given multiple requests to the same endpoint
        long startTime = System.nanoTime();
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(startTime - 10_000_000L);
        when(request.getRequestURI()).thenReturn("/v1/swap");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(200);

        // When processing multiple requests
        interceptor.afterCompletion(request, response, handler, null);
        interceptor.afterCompletion(request, response, handler, null);
        interceptor.afterCompletion(request, response, handler, null);

        // Then metrics aggregate
        Timer timer = registry.find("cashu_mint_requests_duration_seconds")
                .tag("endpoint", "/v1/swap")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3);

        Counter counter = registry.find("cashu_mint_requests_total")
                .tag("endpoint", "/v1/swap")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void differentEndpoints_trackedSeparately() {
        // Given requests to different endpoints
        long startTime = System.nanoTime() - 10_000_000L;

        // Request to /v1/swap
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(startTime);
        when(request.getRequestURI()).thenReturn("/v1/swap");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(200);
        interceptor.afterCompletion(request, response, handler, null);

        // Request to /v1/info
        when(request.getRequestURI()).thenReturn("/v1/info");
        when(request.getMethod()).thenReturn("GET");
        interceptor.afterCompletion(request, response, handler, null);

        // Then each endpoint has its own metrics
        Timer swapTimer = registry.find("cashu_mint_requests_duration_seconds")
                .tag("endpoint", "/v1/swap")
                .timer();
        assertThat(swapTimer).isNotNull();
        assertThat(swapTimer.count()).isEqualTo(1);

        Timer infoTimer = registry.find("cashu_mint_requests_duration_seconds")
                .tag("endpoint", "/v1/info")
                .timer();
        assertThat(infoTimer).isNotNull();
        assertThat(infoTimer.count()).isEqualTo(1);
    }

    @Test
    void statusGroups_categorizedCorrectly() {
        // Given requests with different status codes
        long startTime = System.nanoTime() - 10_000_000L;
        when(request.getAttribute("cashu.metrics.startTime")).thenReturn(startTime);
        when(request.getRequestURI()).thenReturn("/v1/test");
        when(request.getMethod()).thenReturn("GET");

        // 2xx response
        when(response.getStatus()).thenReturn(200);
        interceptor.afterCompletion(request, response, handler, null);

        // 3xx response
        when(response.getStatus()).thenReturn(302);
        interceptor.afterCompletion(request, response, handler, null);

        // 4xx response
        when(response.getStatus()).thenReturn(404);
        interceptor.afterCompletion(request, response, handler, null);

        // 5xx response
        when(response.getStatus()).thenReturn(503);
        interceptor.afterCompletion(request, response, handler, null);

        // Then each status group is tracked
        assertThat(registry.find("cashu_mint_requests_total").tag("status", "2xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_requests_total").tag("status", "3xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_requests_total").tag("status", "4xx").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_requests_total").tag("status", "5xx").counter().count()).isEqualTo(1.0);
    }
}
