package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.WebhookMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer implementation of the webhook recorder port (issue #342).
 *
 * <p>One series per {@link WebhookEvent.Outcome} is registered eagerly: the
 * label domain is closed by the enum (and by the {@code outcome} CHECK
 * constraint it mirrors), so the full series set is known at startup and an
 * outcome that has not happened yet still reads as 0 rather than as missing.
 */
public class MicrometerWebhookMetricsRecorder implements WebhookMetricsRecorder {

    private static final String METRIC_NAME = "cashu_mint_webhook_event_total";

    private final Map<WebhookEvent.Outcome, Counter> byOutcome =
            new EnumMap<>(WebhookEvent.Outcome.class);

    public MicrometerWebhookMetricsRecorder(MeterRegistry registry) {
        for (WebhookEvent.Outcome outcome : WebhookEvent.Outcome.values()) {
            byOutcome.put(outcome, Counter.builder(METRIC_NAME)
                    .description("Webhook deliveries processed, by outcome (FR-008)")
                    .tag("outcome", outcome.name())
                    .register(registry));
        }
    }

    @Override
    public void event(WebhookEvent.Outcome outcome) {
        byOutcome.get(outcome).increment();
    }
}
