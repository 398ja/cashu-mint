package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.IssuanceMetricsRecorder;

/**
 * Micrometer implementation of the mint/issuance recorder port (issue #342).
 *
 * <p>Counters are registered eagerly in the constructor so the family appears
 * in a scrape before the first failure — a family that only materialises on
 * failure reads as "no data" on a dashboard, which is indistinguishable from
 * the metric being broken.
 */
public class MicrometerIssuanceMetricsRecorder implements IssuanceMetricsRecorder {

    private static final String METRIC_PREFIX = "cashu_mint_issuance_";

    private final Counter quoteExpired;
    private final Counter amountMismatch;
    private final Counter crossCheckFailure;
    private final Counter idempotentReplay;
    private final Counter rateLimitBreach;

    public MicrometerIssuanceMetricsRecorder(MeterRegistry registry) {
        this.quoteExpired = Counter.builder(METRIC_PREFIX + "quote_expired_total")
                .description("Mint requests refused because the quote had expired")
                .register(registry);
        this.amountMismatch = Counter.builder(METRIC_PREFIX + "amount_mismatch_total")
                .description("Mint requests whose blinded outputs did not sum to the quote amount (FR-001)")
                .register(registry);
        this.crossCheckFailure = Counter.builder(METRIC_PREFIX + "cross_check_failure_total")
                .description("Gateway amount cross-checks that failed before the PAID to ISSUING CAS (FR-010)")
                .register(registry);
        this.idempotentReplay = Counter.builder(METRIC_PREFIX + "idempotent_replay_total")
                .description("NUT-19 idempotent replays served from the issuance record")
                .register(registry);
        this.rateLimitBreach = Counter.builder(METRIC_PREFIX + "rate_limit_breach_total")
                .description("Requests rejected by the issuance rate limit")
                .register(registry);
    }

    @Override
    public void quoteExpired() {
        quoteExpired.increment();
    }

    @Override
    public void amountMismatch() {
        amountMismatch.increment();
    }

    @Override
    public void crossCheckFailure() {
        crossCheckFailure.increment();
    }

    @Override
    public void idempotentReplay() {
        idempotentReplay.increment();
    }

    @Override
    public void rateLimitBreach() {
        rateLimitBreach.increment();
    }
}
