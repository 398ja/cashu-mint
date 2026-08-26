package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import xyz.tcheeric.cashu.mint.observability.interceptor.MetricsHandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the gap {@link MetricCatalogueContractTest} leaves open.
 *
 * <p>That guard exempts the runtime-composed families — {@code cashu_mint_requests_*},
 * {@code cashu_mint_task_*}, {@code cashu_mint_lock_*} — because their names are
 * built from request, task and lock identity, so no recorder can declare them
 * up front. The exemption was total, which meant those three families were the
 * only ones nothing checked, and all three drifted:
 *
 * <ul>
 *   <li>every dashboard and alert grouped task metrics by {@code task_name}
 *       while the code tagged them {@code task}, so the series existed but every
 *       {@code sum by (task_name)} collapsed to one unlabelled line;</li>
 *   <li>the timers published no histogram buckets, so Micrometer exported
 *       Prometheus <em>summaries</em> and every {@code histogram_quantile} panel
 *       and latency alert read "No data".</li>
 * </ul>
 *
 * <p>Rather than restate the expected names (a third artefact that can drift in
 * its own right), this test drives the real instrumentation into a real
 * {@link PrometheusMeterRegistry} and asserts against the actual scrape text
 * that the dashboards and alert rules are read from.
 */
class ScrapeContractTest {

    private static final Path REPO_ROOT = Path.of("..");
    private static final Path DASHBOARD_DIR =
            REPO_ROOT.resolve("cashu-mint-observability/docker/grafana/dashboards");
    private static final Path ALERTS =
            REPO_ROOT.resolve("cashu-mint-observability/docker/prometheus/alerts.yml");

    /** The families {@link MetricCatalogueContractTest} exempts, and this one owns. */
    private static final List<String> RUNTIME_COMPOSED_PREFIXES =
            List.of("cashu_mint_requests_", "cashu_mint_task_", "cashu_mint_lock_");

    private static final Pattern DASHBOARD_EXPR =
            Pattern.compile("\"expr\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Captures {@code sum by (task_name, le)} and {@code sum by(endpoint)} alike. */
    private static final Pattern GROUPING_LABELS =
            Pattern.compile("\\bby\\s*\\(([^)]*)\\)");

    /** Captures the {@code status} in {@code cashu_mint_requests_total{status=~"5xx"}}. */
    private static final Pattern SELECTOR_LABELS =
            Pattern.compile("(cashu_mint_[a-z0-9_]+)\\{([^}]*)}");

    private static final Pattern LABEL_KEY = Pattern.compile("([a-z_][a-z0-9_]*)\\s*(?:=~|!~|!=|=)");

    /** {@code le} is synthesised by Prometheus on bucket series, never tagged by code. */
    private static final Set<String> PROMETHEUS_INTRINSIC_LABELS = Set.of("le", "job", "instance");

    /** Applied by {@code management.metrics.tags.*}, not by the recorders under test. */
    private static final Set<String> COMMON_TAGS = Set.of("application", "env");

    private String scrape;

    /**
     * Exercises each runtime-composed family exactly as production does, then
     * captures the resulting exposition text.
     */
    @BeforeEach
    void recordOneOfEachRuntimeComposedMetric() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new MetricsHandlerInterceptor(registry)
                .afterCompletion(getRequest(), getResponse(), new Object(), null);

        new TaskMetrics(registry).recordExecution("SwapTask", 5_000_000L, true, null);
        new TaskMetrics(registry).recordExecution("MeltTask", 5_000_000L, false, "TimeoutException");

        LockMetrics locks = new LockMetrics(registry);
        locks.recordLockWait("quote", 1_000_000L);
        locks.recordLockHold("quote", 1_000_000L);

        scrape = registry.scrape();
    }

    /**
     * Timers must export {@code _bucket} series, because every latency panel and
     * latency alert is a {@code histogram_quantile} over them. A Micrometer timer
     * without {@code publishPercentileHistogram} exports a summary instead, whose
     * count/sum/max satisfy no quantile query.
     */
    @Test
    void everyTimerFamilyExportsHistogramBuckets() {
        Set<String> missing = new TreeSet<>();
        for (String family : List.of(
                "cashu_mint_requests_duration_seconds",
                "cashu_mint_task_duration_seconds",
                "cashu_mint_lock_wait_seconds",
                "cashu_mint_lock_hold_seconds")) {
            if (!scrape.contains(family + "_bucket")) {
                missing.add(family);
            }
        }

        assertThat(missing)
                .as("timer families exporting no _bucket series — every histogram_quantile "
                        + "panel and latency alert over them reads \"No data\"")
                .isEmpty();
    }

    /**
     * Every label a dashboard groups or filters by must actually be present on
     * the scraped series. A {@code sum by (task_name)} over series tagged
     * {@code task} does not error — it silently collapses every task into one
     * unlabelled line, which is why this drifted unnoticed.
     */
    @Test
    void everyDashboardLabelExistsOnTheScrapedSeries() throws IOException {
        Map<String, Set<String>> unknown = new LinkedHashMap<>();

        try (Stream<Path> dashboards = Files.list(DASHBOARD_DIR)) {
            for (Path dashboard : dashboards.filter(p -> p.toString().endsWith(".json")).toList()) {
                Set<String> bad = unknownLabels(promQlOf(Files.readString(dashboard, StandardCharsets.UTF_8)));
                if (!bad.isEmpty()) {
                    unknown.put(dashboard.getFileName().toString(), bad);
                }
            }
        }

        assertThat(unknown)
                .as("dashboard panels group or filter by labels the metrics do not carry")
                .isEmpty();
    }

    /** An alert grouped by a label that does not exist aggregates away its own context. */
    @Test
    void everyAlertLabelExistsOnTheScrapedSeries() throws IOException {
        assertThat(unknownLabels(Files.readString(ALERTS, StandardCharsets.UTF_8)))
                .as("alert rules group or filter by labels the metrics do not carry")
                .isEmpty();
    }

    /** The guard has to be able to fail, or it is decorative. */
    @Test
    void guardRejectsALabelNoSeriesCarries() {
        assertThat(unknownLabels("sum by (task_name, nonexistent_label) (cashu_mint_task_success_total)"))
                .containsExactly("nonexistent_label");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /**
     * Reports label keys used against a runtime-composed family that no scraped
     * series of that family carries.
     */
    private Set<String> unknownLabels(String promQl) {
        Set<String> present = labelsPresentInScrape();
        Set<String> unknown = new TreeSet<>();

        if (!referencesRuntimeComposedFamily(promQl)) {
            return unknown;
        }

        for (String label : labelsUsedIn(promQl)) {
            if (present.contains(label)
                    || PROMETHEUS_INTRINSIC_LABELS.contains(label)
                    || COMMON_TAGS.contains(label)) {
                continue;
            }
            unknown.add(label);
        }
        return unknown;
    }

    private boolean referencesRuntimeComposedFamily(String promQl) {
        return RUNTIME_COMPOSED_PREFIXES.stream().anyMatch(promQl::contains);
    }

    private Set<String> labelsUsedIn(String promQl) {
        Set<String> used = new LinkedHashSet<>();

        Matcher grouping = GROUPING_LABELS.matcher(promQl);
        while (grouping.find()) {
            for (String label : grouping.group(1).split(",")) {
                String trimmed = label.trim();
                if (!trimmed.isEmpty()) {
                    used.add(trimmed);
                }
            }
        }

        Matcher selector = SELECTOR_LABELS.matcher(promQl);
        while (selector.find()) {
            if (!referencesRuntimeComposedFamily(selector.group(1))) {
                continue;
            }
            Matcher key = LABEL_KEY.matcher(selector.group(2));
            while (key.find()) {
                used.add(key.group(1));
            }
        }

        return used;
    }

    /** Every label key carried by a runtime-composed series in the scrape. */
    private Set<String> labelsPresentInScrape() {
        Set<String> present = new LinkedHashSet<>();
        Matcher matcher = SELECTOR_LABELS.matcher(scrape);
        while (matcher.find()) {
            if (!referencesRuntimeComposedFamily(matcher.group(1))) {
                continue;
            }
            Matcher key = LABEL_KEY.matcher(matcher.group(2));
            while (key.find()) {
                present.add(key.group(1));
            }
        }
        return present;
    }

    private String promQlOf(String dashboardJson) {
        StringBuilder queries = new StringBuilder();
        Matcher matcher = DASHBOARD_EXPR.matcher(dashboardJson);
        while (matcher.find()) {
            queries.append(matcher.group(1)).append('\n');
        }
        return queries.toString();
    }

    private HttpServletRequest getRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/swap");
        request.setAttribute("cashu.metrics.startTime", System.nanoTime());
        return request;
    }

    private HttpServletResponse getResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);
        return response;
    }
}
