package xyz.tcheeric.cashu.mint.rest.spec001;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.proto.metrics.IssuanceMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.WebhookMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #342 — asserts the mint/issuance and webhook areas' metric names as
 * they appear on the real {@code /actuator/prometheus} scrape.
 *
 * <p>A renamed or mis-declared series is only visibly broken at the scrape,
 * which is the surface dashboards and alert rules read; asserting against the
 * in-process registry would pass on a metric no operator can reach.
 */
@TestPropertySource(properties = {
        "CASHU_MINT_MANAGEMENT_PORT=0",
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics"
})
class IssuanceWebhookRecorderMetricsIT extends AbstractMintDurableIT {

    @Value("${local.management.port}")
    int managementPort;

    /**
     * {@code MetricRecorders} is a JVM-global, last-writer-wins static that
     * every observability-enabled context writes on refresh. Pin the globals
     * to this context's beans so we scrape the counters we increment.
     */
    @Autowired
    IssuanceMetricsRecorder issuanceMetricsRecorder;

    @Autowired
    WebhookMetricsRecorder webhookMetricsRecorder;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void pinRecorders() {
        MetricRecorders.registerIssuance(issuanceMetricsRecorder);
        MetricRecorders.registerWebhook(webhookMetricsRecorder);
    }

    /** Every issuance family is scrapeable before it first fires. */
    @Test
    void everyIssuanceSeriesIsRegisteredBeforeItFires() {
        String scrape = scrape();

        for (String name : new String[]{
                "cashu_mint_issuance_quote_expired",
                "cashu_mint_issuance_amount_mismatch",
                "cashu_mint_issuance_cross_check_failure",
                "cashu_mint_issuance_idempotent_replay",
                "cashu_mint_issuance_rate_limit_breach"}) {
            assertThat(scrape).as(name).containsPattern("# TYPE " + name + "(_total)? counter");
        }
    }

    /** Increments reach the scrape under the declared name. */
    @Test
    void issuanceIncrementsAreVisibleOnTheScrape() {
        double before = valueOf(scrape(), "cashu_mint_issuance_amount_mismatch_total", null);

        MetricRecorders.issuance().amountMismatch();
        MetricRecorders.issuance().amountMismatch();

        assertThat(valueOf(scrape(), "cashu_mint_issuance_amount_mismatch_total", null) - before)
                .isEqualTo(2.0);
    }

    /** The legacy path label was a second encoding of the area; it must be gone. */
    @Test
    void issuanceSeriesCarryNoPathLabel() {
        MetricRecorders.issuance().quoteExpired();

        assertThat(labelsOf(scrape(), "cashu_mint_issuance_quote_expired_total", null))
                .doesNotContain("path=");
    }

    /** One webhook series per outcome, all present before any delivery lands. */
    @Test
    void everyWebhookOutcomeSeriesIsRegistered() {
        String scrape = scrape();

        assertThat(scrape).containsPattern("# TYPE cashu_mint_webhook_event(_total)? counter");
        for (WebhookEvent.Outcome outcome : WebhookEvent.Outcome.values()) {
            assertThat(labelsOf(scrape, "cashu_mint_webhook_event_total", outcome.name()))
                    .as("outcome=%s must be pre-registered", outcome.name())
                    .contains("outcome=\"" + outcome.name() + "\"");
        }
    }

    /** Recording an outcome moves only that outcome's series. */
    @Test
    void webhookOutcomeIncrementIsVisibleOnTheScrape() {
        double before = valueOf(scrape(), "cashu_mint_webhook_event_total", "duplicate");

        MetricRecorders.webhook().event(WebhookEvent.Outcome.duplicate);

        assertThat(valueOf(scrape(), "cashu_mint_webhook_event_total", "duplicate") - before)
                .isEqualTo(1.0);
    }

    /** The pre-recorder names must not survive anywhere in the exposition. */
    @Test
    void legacyUnprefixedNamesAreGone() {
        String scrape = scrape();

        assertThat(scrape).doesNotContain("cashu_mint_quote_expired_total");
        assertThat(scrape).doesNotContain("cashu_mint_amount_mismatch_total");
        assertThat(scrape).doesNotContain("cashu_mint_quote_cross_check_failures_total");
        assertThat(scrape).doesNotContain("cashu_mint_idempotent_replay_total");
    }

    private String scrape() {
        return restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class).getBody();
    }

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
