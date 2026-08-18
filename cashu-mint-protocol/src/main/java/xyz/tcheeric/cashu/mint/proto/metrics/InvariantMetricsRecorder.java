package xyz.tcheeric.cashu.mint.proto.metrics;

import java.util.function.Supplier;

/**
 * Typed recorder port for the operational-invariant gauges — see
 * {@code docs/adr/0002-db-derived-gauges-for-operational-invariants.md} and
 * issue #343.
 *
 * <p>Unlike the counter recorders, the values here are <em>bound</em> rather
 * than incremented: an invariant is a duration in database state, re-derived
 * by a poller, so the meter reads a supplier instead of accumulating events.
 * Binding is one-shot at poller construction; the poller then just updates the
 * value the supplier reads.
 *
 * <p>This exists so the poller in {@code cashu-mint-jpa} never touches a
 * {@code MeterRegistry}: the metric names stay declared in the observability
 * module with every other name, which is what makes the catalogue checkable
 * (#347).
 */
public interface InvariantMetricsRecorder {

    /**
     * Binds the Stuck Payment gauge to {@code value}. Emits
     * {@code cashu_mint_melt_stuck_payment_unknown}: melt sagas parked in
     * {@code PAYMENT_UNKNOWN} past the configured TTL.
     *
     * @param value supplier read on every scrape
     */
    void bindStuckPaymentUnknown(Supplier<Number> value);

    /**
     * An invariant poll threw. Emits
     * {@code cashu_mint_invariant_poll_failures_total} — without it a failing
     * poll would hold a stale gauge value and silently disarm the alert.
     */
    void pollFailed();
}
