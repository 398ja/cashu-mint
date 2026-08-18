package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestEchoConfig;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #341 — asserts the voucher area's metric names and labels as they
 * appear on the real {@code /actuator/prometheus} scrape, not as they look in
 * the in-process registry.
 *
 * <p>This is the test that would have caught the original defect: a renamed or
 * mis-declared series is only visibly broken at the scrape, which is the
 * surface dashboards and alert rules actually read.
 */
@Import(VoucherTestEchoConfig.class)
@TestPropertySource(properties = {
        "CASHU_MINT_MANAGEMENT_PORT=0",
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "cashu.mint.voucher.rate-limit-tokens-per-minute=2"
})
class VoucherRecorderMetricsIT extends AbstractVoucherDurableIT {

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    /**
     * {@code MetricRecorders} is a JVM-global, last-writer-wins static, and
     * every observability-enabled context registers into it on refresh. Pin
     * the global to this context's bean so the counters we scrape are the
     * ones our call sites increment, whatever order the cached test contexts
     * happened to refresh in.
     */
    @Autowired
    VoucherMetricsRecorder voucherMetricsRecorder;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void pinRecorder() {
        MetricRecorders.registerVoucher(voucherMetricsRecorder);
    }

    /**
     * Every declared voucher series must be scrapeable before it first fires,
     * including one per reason and one per funding source — a family that only
     * materialises on failure is indistinguishable from a broken exporter.
     */
    @Test
    void everyVoucherSeriesIsRegisteredBeforeItFires() {
        String scrape = scrape();

        assertThat(scrape).containsPattern("# TYPE cashu_mint_voucher_rejected(_total)? counter");
        assertThat(scrape).containsPattern("# TYPE cashu_mint_voucher_issued(_total)? counter");
        assertThat(scrape).containsPattern("# TYPE cashu_mint_voucher_iou_issued(_total)? counter");
        assertThat(scrape).containsPattern("# TYPE cashu_mint_voucher_lazy_funding(_total)? counter");
        assertThat(scrape).containsPattern("# TYPE cashu_mint_voucher_rate_limit_breach(_total)? counter");

        for (VoucherRejectionReason reason : VoucherRejectionReason.values()) {
            assertThat(labelsOf(scrape, "cashu_mint_voucher_rejected_total", reason.label()))
                    .as("reason=%s must be pre-registered", reason.label())
                    .contains("reason=\"" + reason.label() + "\"");
        }
        for (VoucherFundingSource source : VoucherFundingSource.values()) {
            assertThat(labelsOf(scrape, "cashu_mint_voucher_issued_total", source.name()))
                    .as("funding_source=%s must be pre-registered", source.name())
                    .contains("funding_source=\"" + source.name() + "\"");
        }
    }

    /** The collapsed counter carries the reason as a label, one series per enum value. */
    @Test
    void rejectionsIncrementTheReasonLabelledSeries() {
        double before = valueOf(scrape(), "cashu_mint_voucher_rejected_total", "funding_required");

        MetricRecorders.voucher().rejected(VoucherRejectionReason.FUNDING_REQUIRED);
        MetricRecorders.voucher().rejected(VoucherRejectionReason.FUNDING_REQUIRED);
        MetricRecorders.voucher().rejected(VoucherRejectionReason.IOU_NOT_PERMITTED);

        String scrape = scrape();
        assertThat(valueOf(scrape, "cashu_mint_voucher_rejected_total", "funding_required") - before)
                .isEqualTo(2.0);
        assertThat(valueOf(scrape, "cashu_mint_voucher_rejected_total", "iou_not_permitted"))
                .isGreaterThanOrEqualTo(1.0);
    }

    /**
     * A real 429 from {@link xyz.tcheeric.cashu.mint.rest.voucher.VoucherRateLimitFilter}
     * must reach the counter — and the series must NOT carry a principal
     * label (cardinality + spec-004 data minimisation; see
     * {@code VoucherMetricsRecorder}).
     */
    @Test
    void rateLimitBreachIncrementsAndCarriesNoPrincipalLabel() {
        double before = valueOf(scrape(), "cashu_mint_voucher_rate_limit_breach_total", null);

        // Capacity is pinned to 2; the third call is refused.
        post("{\"i\":1}");
        post("{\"i\":2}");
        ResponseEntity<String> denied = post("{\"i\":3}");
        assertThat(denied.getStatusCode().value()).isEqualTo(429);

        String scrape = scrape();
        assertThat(valueOf(scrape, "cashu_mint_voucher_rate_limit_breach_total", null) - before)
                .isEqualTo(1.0);
        assertThat(labelsOf(scrape, "cashu_mint_voucher_rate_limit_breach_total", null))
                .as("the breaching principal belongs in the logs, never in a label")
                .doesNotContain("principal");
    }

    /** The plural prefix died with the dead metric classes; nothing may reintroduce it. */
    @Test
    void scrapeContainsNoPluralVoucherPrefix() {
        assertThat(scrape()).doesNotContain("cashu_mint_vouchers_");
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        try {
            return restTemplate.postForEntity("http://localhost:" + port + "/v1/vouchers/_test_echo",
                    new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
        }
    }

    private String scrape() {
        return restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class).getBody();
    }

    /**
     * Matches one sample line of {@code metric}, optionally restricted to the
     * series whose label block contains {@code labelFilter}.
     */
    private Matcher sampleMatcher(String scrape, String metric, String labelFilter) {
        String labels = labelFilter == null ? "[^}]*" : "[^}]*" + Pattern.quote(labelFilter) + "[^}]*";
        return Pattern.compile("^" + Pattern.quote(metric) + "\\{(" + labels + ")}\\s+([0-9.E+-]+)$",
                Pattern.MULTILINE).matcher(scrape == null ? "" : scrape);
    }

    private String labelsOf(String scrape, String metric, String labelFilter) {
        Matcher matcher = sampleMatcher(scrape, metric, labelFilter);
        return matcher.find() ? matcher.group(1) : "";
    }

    private double valueOf(String scrape, String metric, String labelFilter) {
        Matcher matcher = sampleMatcher(scrape, metric, labelFilter);
        return matcher.find() ? Double.parseDouble(matcher.group(2)) : 0.0;
    }
}
