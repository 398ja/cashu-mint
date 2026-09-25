package xyz.tcheeric.cashu.mint.observability.interceptor;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the measurable range of {@code cashu_mint_requests_duration_seconds}.
 *
 * <p>Asserted against the Prometheus scrape text rather than a {@code SimpleMeterRegistry}
 * snapshot, because only the Prometheus registry materialises the {@code le} bucket series that
 * {@code histogram_quantile} reads, and those series are what the latency SLO alert queries.
 *
 * <p>The range matters because a request slower than the top finite bucket is indistinguishable
 * from one that took an hour: {@code histogram_quantile} cannot interpolate past the highest
 * finite {@code le}, so it answers exactly that boundary. Measured on staging with a 10s ceiling,
 * {@code /v1/checkstate} put 10 of 24 observations above {@code le=10.0}, pinning p99 at 10.00s
 * and understating every {@code CashuMintLatencySLOBreach} firing.
 */
class RequestLatencyBucketRangeTest {

    private static final String BUCKET_SERIES = "cashu_mint_requests_duration_seconds_bucket";

    private static final Pattern BUCKET_BOUNDARY =
            Pattern.compile(BUCKET_SERIES + "\\{[^}]*le=\"([^\"]+)\"[^}]*}\\s+(\\S+)");

    private static final double SLOW_REQUEST_SECONDS = 12.0;

    // A 12s request must land in a finite bucket, so the tail above the old 10s ceiling becomes
    // visible to histogram_quantile instead of collapsing into +Inf.
    @Test
    void aRequestSlowerThanTenSecondsFallsInsideAFiniteBucket() {
        List<BucketCount> buckets = scrapeBucketsAfterRequestTaking(SLOW_REQUEST_SECONDS);

        assertThat(buckets).isNotEmpty();
        assertThat(highestFiniteBoundary(buckets))
                .as("top finite bucket boundary, above which p99 is unmeasurable")
                .isGreaterThan(SLOW_REQUEST_SECONDS);
        assertThat(countedAtOrBelow(buckets, SLOW_REQUEST_SECONDS))
                .as("cumulative count at le=12.0, which must exclude the 12s observation")
                .isZero();
        assertThat(countedAtOrBelow(buckets, highestFiniteBoundary(buckets)))
                .as("cumulative count at the top finite bucket, which must include it")
                .isEqualTo(1.0);
    }

    // The ceiling is the deliberate 30s, not merely "more than 12s", so an accidental narrowing
    // back toward 10s is caught rather than silently passing.
    @Test
    void theHighestFiniteBucketReachesTheThirtySecondCeiling() {
        List<BucketCount> buckets = scrapeBucketsAfterRequestTaking(SLOW_REQUEST_SECONDS);

        assertThat(highestFiniteBoundary(buckets)).isGreaterThanOrEqualTo(30.0);
    }

    private static List<BucketCount> scrapeBucketsAfterRequestTaking(double durationSeconds) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsHandlerInterceptor interceptor = new MetricsHandlerInterceptor(registry);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkstate");
        long elapsedNanos = (long) (durationSeconds * 1_000_000_000L);
        request.setAttribute("cashu.metrics.startTime", System.nanoTime() - elapsedNanos);
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        return parseBuckets(registry.scrape());
    }

    private static List<BucketCount> parseBuckets(String scrape) {
        List<BucketCount> buckets = new ArrayList<>();
        Matcher matcher = BUCKET_BOUNDARY.matcher(scrape);
        while (matcher.find()) {
            String boundary = matcher.group(1);
            if (!"+Inf".equals(boundary)) {
                buckets.add(new BucketCount(Double.parseDouble(boundary),
                        Double.parseDouble(matcher.group(2))));
            }
        }
        return buckets;
    }

    private static double highestFiniteBoundary(List<BucketCount> buckets) {
        return buckets.stream().mapToDouble(BucketCount::boundarySeconds).max().orElse(0.0);
    }

    private static double countedAtOrBelow(List<BucketCount> buckets, double boundarySeconds) {
        return buckets.stream()
                .filter(bucket -> bucket.boundarySeconds() <= boundarySeconds)
                .max((left, right) -> Double.compare(left.boundarySeconds(), right.boundarySeconds()))
                .map(BucketCount::cumulativeCount)
                .orElse(0.0);
    }

    /** One scraped {@code le} boundary and its cumulative count. */
    private record BucketCount(double boundarySeconds, double cumulativeCount) {
    }
}
