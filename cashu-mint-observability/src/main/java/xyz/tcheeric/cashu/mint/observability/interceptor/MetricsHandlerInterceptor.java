package xyz.tcheeric.cashu.mint.observability.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
 * <p>The {@code endpoint} tag is the route pattern Spring MVC matched
 * ({@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}), for example
 * {@code /v1/mint/quote/{method}/{quote_id}}, never the raw path. A request no route matched is
 * tagged {@value #UNMATCHED}. The raw path is unbounded (one series per id) and a quote status
 * path carries the quote id, a bearer claim on a paid unlocked quote (cashu-mint#531). The
 * regexes this used to normalise the path with were {@code $}-anchored, so a trailing slash
 * ({@code /v1/mint/quote/bolt11/<id>/}) slipped past them and put the id into the tag.
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_requests_*}
 */
@Slf4j
public class MetricsHandlerInterceptor implements HandlerInterceptor {

    private static final String METRIC_PREFIX = "cashu_mint_requests_";
    private static final String START_TIME_ATTR = "cashu.metrics.startTime";
    /** The endpoint tag of a request that matched no route: a 404 probe, a static resource. */
    static final String UNMATCHED = "unmatched";

    /**
     * Bucket boundaries for the request latency histogram. Without
     * {@code publishPercentileHistogram} Micrometer exports a Prometheus
     * <em>summary</em>, which carries no {@code _bucket} series, so every
     * {@code histogram_quantile} panel and latency SLO alert reads "No data".
     */
    private static final Duration MINIMUM_EXPECTED_DURATION = Duration.ofMillis(1);

    /**
     * The ceiling is 30s because a 10s ceiling made the real tail unmeasurable. Measured on
     * staging for {@code /v1/checkstate}: {@code le=10.0} held 14 of 24 observations while
     * {@code +Inf} held all 24, so 10 requests landed above the top finite bucket and
     * {@code histogram_quantile} could only ever answer 10.00s for p99. Anything above the top
     * finite bucket is interpolated against {@code +Inf}, so the latency SLO alert understated
     * every breach it fired on.
     */
    private static final Duration MAXIMUM_EXPECTED_DURATION = Duration.ofSeconds(30);

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
            // The matched route, never the raw URI: a quote status path carries the quote id, a
            // bearer claim on a paid unlocked quote (cashu-mint#531).
            log.trace("Request started: {} {}", request.getMethod(), endpointOf(request));
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
        String endpoint = endpointOf(request);
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
     * The route pattern Spring MVC matched for {@code request}, or {@value #UNMATCHED}. Interceptors
     * run after handler mapping, so the attribute is set for every request a controller handles.
     */
    static String endpointOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route && !route.isBlank() ? route : UNMATCHED;
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
                        .publishPercentileHistogram()
                        .minimumExpectedValue(MINIMUM_EXPECTED_DURATION)
                        .maximumExpectedValue(MAXIMUM_EXPECTED_DURATION)
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
