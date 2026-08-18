package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;

import java.util.function.Supplier;

/**
 * Micrometer implementation of the invariant recorder port (issue #343),
 * backing the DB-derived gauges of ADR 0002.
 */
public class MicrometerInvariantMetricsRecorder implements InvariantMetricsRecorder {

    private final MeterRegistry registry;
    private final Counter pollFailures;

    public MicrometerInvariantMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        this.pollFailures = Counter.builder("cashu_mint_invariant_poll_failures_total")
                .description("Invariant poll attempts that failed; a non-zero rate means the gauges are stale")
                .register(registry);
    }

    @Override
    public void bindStuckPaymentUnknown(Supplier<Number> value) {
        Gauge.builder("cashu_mint_melt_stuck_payment_unknown", value)
                .description("Melt sagas stuck in PAYMENT_UNKNOWN past cashu.mint.melt.payment-unknown-ttl "
                        + "(see MeltSagaJpaRepository#countStuckPaymentUnknown)")
                .register(registry);
    }

    @Override
    public void pollFailed() {
        pollFailures.increment();
    }
}
