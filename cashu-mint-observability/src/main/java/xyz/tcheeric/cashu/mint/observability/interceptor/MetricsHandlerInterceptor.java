package xyz.tcheeric.cashu.mint.observability.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * HTTP request metrics interceptor for Cashu Mint REST endpoints.
 *
 * <p>This interceptor tracks:
 * <ul>
 *   <li>Request count per endpoint, method, and status</li>
 *   <li>Request duration (latency) per endpoint and method</li>
 *   <li>Error count per endpoint and error type</li>
 * </ul>
 *
 * <p>Endpoint paths are normalized to prevent high cardinality:
 * <ul>
 *   <li>{@code /v1/keys/abc123} → {@code /v1/keys/{keyset_id}}</li>
 *   <li>{@code /v1/mint/quote/bolt11/uuid} → {@code /v1/mint/quote/{method}/{quote_id}}</li>
 *   <li>{@code /v1/melt/quote/bolt11/uuid} → {@code /v1/melt/quote/{method}/{quote_id}}</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_requests_*}
 */
@Slf4j
public class MetricsHandlerInterceptor implements HandlerInterceptor {

    private static final String METRIC_PREFIX = "cashu_mint_requests_";
    private static final String START_TIME_ATTR = "cashu.metrics.startTime";

    // Patterns for normalizing variable path segments
    // Pattern to match keyset endpoints: /v1/keys/{keyset_id} or /v1/keys/keyset/{keyset_id}
    private static final Pattern KEYSET_ID_PATTERN = Pattern.compile("/v1/keys(/keyset)?/[^/]+$");
    // Pattern to match voucher quote: /v1/mint/quote/voucher/{method}/{quote_id}
    private static final Pattern VOUCHER_QUOTE_ID_PATTERN = Pattern.compile("/v1/mint/quote/voucher/([^/]+)/[^/]+$");
    // Pattern to match mint quote: /v1/mint/quote/{method}/{quote_id}
    private static final Pattern MINT_QUOTE_ID_PATTERN = Pattern.compile("/v1/mint/quote/([^/]+)/[^/]+$");
    // Pattern to match melt quote: /v1/melt/quote/{method}/{quote_id}
    private static final Pattern MELT_QUOTE_ID_PATTERN = Pattern.compile("/v1/melt/quote/([^/]+)/[^/]+$");

    private final MeterRegistry registry;

    // Cached counters and timers to avoid re-registration
    private final ConcurrentHashMap<String, Timer> requestTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    /**
     * Creates a new MetricsHandlerInterceptor.
     *
     * @param registry the Micrometer registry to use
     */
    public MetricsHandlerInterceptor(MeterRegistry registry) {
        this.registry = registry;
        log.debug("MetricsHandlerInterceptor initialized");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Record start time for duration measurement
        request.setAttribute(START_TIME_ATTR, System.nanoTime());

        if (log.isTraceEnabled()) {
            log.trace("Request started: {} {}", request.getMethod(), request.getRequestURI());
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime == null) {
            // preHandle was not called (e.g., filter rejected request)
            return;
        }

        long durationNanos = System.nanoTime() - startTime;
        String endpoint = normalizeEndpoint(request.getRequestURI());
        String method = request.getMethod();
        int status = response.getStatus();
        String statusGroup = getStatusGroup(status);

        // Record duration
        getTimer(endpoint, method).record(durationNanos, TimeUnit.NANOSECONDS);

        // Record request count
        getCounter(endpoint, method, statusGroup).increment();

        // Record error if applicable
        if (ex != null || status >= 400) {
            String errorType = ex != null ? ex.getClass().getSimpleName() : "http_" + status;
            getErrorCounter(endpoint, errorType).increment();
        }

        if (log.isTraceEnabled()) {
            log.trace("Request completed: {} {} -> {} in {} ms",
                    method, endpoint, status, durationNanos / 1_000_000);
        }
    }

    /**
     * Normalizes an endpoint path to prevent high cardinality.
     *
     * <p>Variable path segments (IDs, UUIDs) are replaced with placeholders.
     *
     * @param uri the raw request URI
     * @return normalized endpoint path
     */
    String normalizeEndpoint(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }

        String normalized = uri;

        // Normalize voucher quote ID (must be before mint quote)
        // /v1/mint/quote/voucher/bolt11/uuid -> /v1/mint/quote/voucher/{method}/{quote_id}
        if (VOUCHER_QUOTE_ID_PATTERN.matcher(normalized).find()) {
            normalized = VOUCHER_QUOTE_ID_PATTERN.matcher(normalized)
                    .replaceAll("/v1/mint/quote/voucher/{method}/{quote_id}");
            return normalized;
        }

        // Normalize mint quote ID: /v1/mint/quote/bolt11/uuid -> /v1/mint/quote/{method}/{quote_id}
        if (MINT_QUOTE_ID_PATTERN.matcher(normalized).find()) {
            normalized = MINT_QUOTE_ID_PATTERN.matcher(normalized)
                    .replaceAll("/v1/mint/quote/{method}/{quote_id}");
            return normalized;
        }

        // Normalize melt quote ID: /v1/melt/quote/bolt11/uuid -> /v1/melt/quote/{method}/{quote_id}
        if (MELT_QUOTE_ID_PATTERN.matcher(normalized).find()) {
            normalized = MELT_QUOTE_ID_PATTERN.matcher(normalized)
                    .replaceAll("/v1/melt/quote/{method}/{quote_id}");
            return normalized;
        }

        // Normalize keyset ID: /v1/keys/abc123 -> /v1/keys/{keyset_id}
        // Also handles: /v1/keys/keyset/abc123 -> /v1/keys/keyset/{keyset_id}
        if (normalized.startsWith("/v1/keys/keyset/")) {
            normalized = "/v1/keys/keyset/{keyset_id}";
        } else if (KEYSET_ID_PATTERN.matcher(normalized).find()) {
            normalized = "/v1/keys/{keyset_id}";
        }

        return normalized;
    }

    /**
     * Gets the status group for a response status code.
     *
     * @param status HTTP status code
     * @return status group (1xx, 2xx, 3xx, 4xx, 5xx)
     */
    private String getStatusGroup(int status) {
        if (status < 200) return "1xx";
        if (status < 300) return "2xx";
        if (status < 400) return "3xx";
        if (status < 500) return "4xx";
        return "5xx";
    }

    private Timer getTimer(String endpoint, String method) {
        String key = endpoint + "_" + method;
        return requestTimers.computeIfAbsent(key, k ->
                Timer.builder(METRIC_PREFIX + "duration_seconds")
                        .description("HTTP request duration")
                        .tag("endpoint", endpoint)
                        .tag("method", method)
                        .minimumExpectedValue(java.time.Duration.ofMillis(1))
                        .register(registry));
    }

    private Counter getCounter(String endpoint, String method, String statusGroup) {
        String key = endpoint + "_" + method + "_" + statusGroup;
        return requestCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "total")
                        .description("Total HTTP requests")
                        .tag("endpoint", endpoint)
                        .tag("method", method)
                        .tag("status", statusGroup)
                        .register(registry));
    }

    private Counter getErrorCounter(String endpoint, String errorType) {
        String key = endpoint + "_" + errorType;
        return errorCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + "errors_total")
                        .description("HTTP request errors")
                        .tag("endpoint", endpoint)
                        .tag("error_type", errorType)
                        .register(registry));
    }
}
