package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.IssuanceMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MeltMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.WebhookMetricsRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Issue #347 — makes the original defect impossible to reintroduce.
 *
 * <p>The root cause behind #337 was that nothing connected a declared metric
 * to a consumer, in either direction: a metric class could exist with no
 * callers, and a dashboard could query a name no code declares. Both happened,
 * and both survived CI for a long time. This test fails the build on either.
 *
 * <p>Shape follows the prior art in this repo — the spec-004 dashboard and
 * disclosure-document contract tests, and the NUT advertisement guards.
 */
class MetricCatalogueContractTest {

    private static final Path REPO_ROOT = Path.of("..");
    private static final Path DASHBOARD_DIR =
            REPO_ROOT.resolve("cashu-mint-observability/docker/grafana/dashboards");
    private static final Path ALERTS = REPO_ROOT.resolve("cashu-mint-observability/docker/prometheus/alerts.yml");

    /** Modules whose {@code src/main} counts as a production call site. */
    private static final List<String> PRODUCTION_MODULES = List.of(
            "cashu-mint-protocol", "cashu-mint-jpa", "cashu-mint-rest", "cashu-mint-webhook");

    private static final Pattern METRIC_REFERENCE = Pattern.compile("cashu_mint_[a-z0-9_]+");

    /**
     * Grafana stores a panel's PromQL in its {@code expr} field. Scanning the
     * whole JSON instead would sweep up identifiers that merely look like
     * metric names — the spec-004 IOU dashboard queries Postgres through the
     * {@code cashu_mint_grafana_ro} role, which is a database role, not a
     * series.
     */
    private static final Pattern DASHBOARD_EXPR = Pattern.compile("\"expr\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Families whose names are composed at runtime from request, task or lock
     * identity, so they cannot be declared on a recorder up front. Anything
     * outside this list must resolve to a declaration.
     */
    private static final List<String> RUNTIME_COMPOSED_PREFIXES = List.of(
            "cashu_mint_requests_", "cashu_mint_task_", "cashu_mint_lock_");

    /** Prometheus suffixes a query may append to a declared meter name. */
    private static final List<String> QUERY_SUFFIXES =
            List.of("_bucket", "_count", "_sum", "_total", "_created", "_max");

    // ---------------------------------------------------------------
    // Direction 1: every declared metric has a production call site
    // ---------------------------------------------------------------

    /**
     * A metric declared on a recorder but never recorded is exactly the dead
     * instrumentation this spec deleted: it charts as a flat zero, which reads
     * identically to "nothing bad is happening".
     */
    @Test
    void everyDeclaredMetricHasAProductionCallSite() throws IOException {
        String productionSources = readProductionSources();
        Map<String, String> metricByMethod = metricByRecorderMethod();
        assertThat(metricByMethod).as("method-to-metric mapping must not be empty").isNotEmpty();

        List<String> unused = new ArrayList<>();
        metricByMethod.forEach((portAndMethod, metric) -> {
            String method = portAndMethod.substring(portAndMethod.indexOf('#') + 1);
            if (!productionSources.contains("." + method + "(")) {
                unused.add(metric + " (declared by " + portAndMethod + ", no call site in "
                        + String.join(", ", PRODUCTION_MODULES) + ")");
            }
        });

        assertThat(unused)
                .as("metrics declared on a recorder with no production call site — either wire "
                        + "them up or delete the declaration")
                .isEmpty();
    }

    // ---------------------------------------------------------------
    // Direction 2: every charted / alerted name resolves to a declaration
    // ---------------------------------------------------------------

    /** A dashboard querying an undeclared name renders "no data" forever. */
    @Test
    void everyDashboardMetricResolvesToADeclaration() throws IOException {
        Set<String> declared = declaredNames();
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();

        try (Stream<Path> dashboards = Files.list(DASHBOARD_DIR)) {
            for (Path dashboard : dashboards.filter(p -> p.toString().endsWith(".json")).toList()) {
                Set<String> bad = unresolvedNames(
                        promQlOf(Files.readString(dashboard, StandardCharsets.UTF_8)), declared);
                if (!bad.isEmpty()) {
                    unresolved.put(dashboard.getFileName().toString(), bad);
                }
            }
        }

        assertThat(unresolved)
                .as("dashboard panels query metric names no recorder declares")
                .isEmpty();
    }

    /** An alert on an undeclared name never fires — the worst failure mode here. */
    @Test
    void everyAlertRuleMetricResolvesToADeclaration() throws IOException {
        Set<String> unresolved = unresolvedNames(
                Files.readString(ALERTS, StandardCharsets.UTF_8), declaredNames());

        assertThat(unresolved)
                .as("alert rules reference metric names no recorder declares — these alerts "
                        + "can never fire")
                .isEmpty();
    }

    /**
     * The guard has to be able to fail, or it is decorative. Feeding it a name
     * nothing declares must be reported.
     */
    @Test
    void guardRejectsAnUndeclaredName() {
        Set<String> unresolved = unresolvedNames(
                "expr: cashu_mint_totally_made_up_total > 0", declaredNames());

        assertThat(unresolved).containsExactly("cashu_mint_totally_made_up_total");
    }

    /** Runtime-composed families are exempt by design, not by accident. */
    @Test
    void runtimeComposedFamiliesAreExempt() {
        assertThat(unresolvedNames(
                "rate(cashu_mint_requests_total[5m]) and cashu_mint_task_duration_seconds_bucket",
                declaredNames()))
                .isEmpty();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Concatenates every {@code expr} value in a dashboard JSON. */
    private String promQlOf(String dashboardJson) {
        StringBuilder queries = new StringBuilder();
        Matcher matcher = DASHBOARD_EXPR.matcher(dashboardJson);
        while (matcher.find()) {
            queries.append(matcher.group(1)).append('\n');
        }
        return queries.toString();
    }

    private Set<String> declaredNames() {
        return new LinkedHashSet<>(MetricCatalogue.declaredNames());
    }

    private Set<String> unresolvedNames(String content, Set<String> declared) {
        Set<String> unresolved = new TreeSet<>();
        Matcher matcher = METRIC_REFERENCE.matcher(content);
        while (matcher.find()) {
            String name = matcher.group();
            if (RUNTIME_COMPOSED_PREFIXES.stream().anyMatch(name::startsWith) || resolves(name, declared)) {
                continue;
            }
            unresolved.add(name);
        }
        return unresolved;
    }

    private boolean resolves(String name, Set<String> declared) {
        if (declared.contains(name)) {
            return true;
        }
        for (String suffix : QUERY_SUFFIXES) {
            if (name.endsWith(suffix) && declared.contains(name.substring(0, name.length() - suffix.length()))) {
                return true;
            }
            // A counter declared as *_total may also be queried without it.
            if (declared.contains(name + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Maps each recorder port method to the metric it moves, by invoking it
     * against a throwaway registry and seeing which meter appeared or changed.
     * Deriving the mapping instead of listing it is what keeps this test from
     * becoming the fourth artefact that drifts.
     */
    private Map<String, String> metricByRecorderMethod() {
        Map<String, String> mapping = new LinkedHashMap<>();

        mapping.putAll(probe(MeltMetricsRecorder.class, MicrometerMeltMetricsRecorder::new,
                Map.of("insufficientInput", (java.util.function.Consumer<MeltMetricsRecorder>)
                                MeltMetricsRecorder::insufficientInput,
                        "proofsNotBound", MeltMetricsRecorder::proofsNotBound)));

        mapping.putAll(probe(VoucherMetricsRecorder.class, MicrometerVoucherMetricsRecorder::new,
                Map.of("rejected", (java.util.function.Consumer<VoucherMetricsRecorder>) r ->
                                r.rejected(xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason.values()[0]),
                        "issued", r -> r.issued(
                                xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource.values()[0]),
                        "iouIssuanceAttempted", VoucherMetricsRecorder::iouIssuanceAttempted,
                        "lazyFundingCreated", VoucherMetricsRecorder::lazyFundingCreated,
                        "rateLimitBreach", VoucherMetricsRecorder::rateLimitBreach)));

        mapping.putAll(probe(IssuanceMetricsRecorder.class, MicrometerIssuanceMetricsRecorder::new,
                Map.of("quoteExpired", (java.util.function.Consumer<IssuanceMetricsRecorder>)
                                IssuanceMetricsRecorder::quoteExpired,
                        "amountMismatch", IssuanceMetricsRecorder::amountMismatch,
                        "crossCheckFailure", IssuanceMetricsRecorder::crossCheckFailure,
                        "idempotentReplay", IssuanceMetricsRecorder::idempotentReplay,
                        "rateLimitBreach", IssuanceMetricsRecorder::rateLimitBreach)));

        mapping.putAll(probe(WebhookMetricsRecorder.class, MicrometerWebhookMetricsRecorder::new,
                Map.of("event", (java.util.function.Consumer<WebhookMetricsRecorder>) r ->
                        r.event(xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome.values()[0]))));

        mapping.putAll(probe(InvariantMetricsRecorder.class, MicrometerInvariantMetricsRecorder::new,
                Map.of("bindStuckPaymentUnknown", (java.util.function.Consumer<InvariantMetricsRecorder>) r ->
                                r.bindStuckPaymentUnknown(() -> 0),
                        "bindPaymentSentBurnFailed", r -> r.bindPaymentSentBurnFailed(() -> 0),
                        "bindOrphanIssuance", r -> r.bindOrphanIssuance(() -> 0),
                        "pollFailed", InvariantMetricsRecorder::pollFailed)));

        return mapping;
    }

    private <R> Map<String, String> probe(Class<R> port,
                                          java.util.function.Function<SimpleMeterRegistry, ? extends R> factory,
                                          Map<String, ? extends java.util.function.Consumer<R>> calls) {
        Map<String, String> mapping = new LinkedHashMap<>();
        calls.forEach((method, call) -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            R recorder = factory.apply(registry);
            Map<String, Double> before = snapshot(registry);
            call.accept(recorder);
            Map<String, Double> after = snapshot(registry);

            String moved = after.entrySet().stream()
                    .filter(e -> !before.containsKey(e.getKey()) || before.get(e.getKey()) < e.getValue())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            port.getSimpleName() + "#" + method + " moved no meter — it declares nothing"));
            // Keyed by port as well as method: rateLimitBreach() exists on both
            // the voucher and issuance recorders, and a bare-method key would
            // silently drop one of them from the call-site check.
            mapping.put(port.getSimpleName() + "#" + method, moved);
        });
        return mapping;
    }

    private Map<String, Double> snapshot(SimpleMeterRegistry registry) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            double value = 0;
            for (Measurement measurement : meter.measure()) {
                value += measurement.getValue();
            }
            values.merge(meter.getId().getName(), value, Double::sum);
        }
        return values;
    }

    private String readProductionSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String module : PRODUCTION_MODULES) {
            Path main = REPO_ROOT.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }
}
