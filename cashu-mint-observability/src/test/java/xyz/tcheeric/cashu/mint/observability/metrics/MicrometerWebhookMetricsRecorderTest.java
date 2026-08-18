package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.metrics.WebhookMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the webhook recorder port to its outcome-labelled counter. */
class MicrometerWebhookMetricsRecorderTest {

    /**
     * One series per outcome, registered up front: the label domain is closed
     * by the enum, so an outcome that has not happened must read 0 rather than
     * be missing from the scrape.
     */
    @Test
    void registersOneSeriesPerOutcomeEagerly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerWebhookMetricsRecorder(registry);

        for (WebhookEvent.Outcome outcome : WebhookEvent.Outcome.values()) {
            assertThat(registry.find("cashu_mint_webhook_event_total")
                    .tag("outcome", outcome.name()).counter())
                    .as(outcome.name())
                    .isNotNull();
        }
    }

    /** Recording an outcome increments only that outcome's series. */
    @Test
    void recordingAnOutcomeIncrementsOnlyThatSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebhookMetricsRecorder recorder = new MicrometerWebhookMetricsRecorder(registry);

        recorder.event(WebhookEvent.Outcome.accepted);
        recorder.event(WebhookEvent.Outcome.accepted);
        recorder.event(WebhookEvent.Outcome.duplicate);

        assertThat(registry.get("cashu_mint_webhook_event_total")
                .tag("outcome", "accepted").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cashu_mint_webhook_event_total")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_webhook_event_total")
                .tag("outcome", "tamper").counter().count()).isZero();
    }
}
