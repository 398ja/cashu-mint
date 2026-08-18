package xyz.tcheeric.cashu.mint.proto.metrics;

import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

/**
 * Typed recorder port for the webhook domain area — see
 * {@code docs/adr/0001-typed-metric-recorders.md}, issue #342.
 *
 * <p>The area emits a single metric, but it still gets its own recorder rather
 * than being folded into the issuance one: ADR 0001 is one recorder per domain
 * area, and webhook delivery is a separate area with a separate owner. Sharing
 * would make the boundary a matter of convenience rather than of design, which
 * is the habit that produced the scattered catalogue.
 *
 * <p>Implemented by {@code cashu-mint-observability}; reached through
 * {@link MetricRecorders#webhook()}, which never returns {@code null}.
 */
public interface WebhookMetricsRecorder {

    /**
     * A webhook delivery was processed. Emits
     * {@code cashu_mint_webhook_event_total{outcome=...}} — spec 001 FR-008.
     * The label domain is the {@link WebhookEvent.Outcome} enum, which mirrors
     * the {@code outcome} CHECK constraint on {@code webhook_event}, so the
     * series set is bounded by the schema.
     *
     * @param outcome how the delivery was classified
     */
    void event(WebhookEvent.Outcome outcome);
}
