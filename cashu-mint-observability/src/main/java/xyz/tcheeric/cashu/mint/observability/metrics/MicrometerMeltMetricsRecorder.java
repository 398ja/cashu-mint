package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.MeltMetricsRecorder;

/**
 * Micrometer implementation of the melt-area recorder port.
 *
 * <p>Counters are registered eagerly in the constructor so the melt family
 * appears in a scrape before the first rejection — a family that only
 * materialises on failure reads as "no data" on a dashboard, which is
 * indistinguishable from the metric being broken.
 *
 * <p>Names follow the mandatory {@code cashu_mint_<area>_<event>_total}
 * convention.
 */
public class MicrometerMeltMetricsRecorder implements MeltMetricsRecorder {

    private static final String METRIC_PREFIX = "cashu_mint_melt_";

    private final Counter insufficientInput;
    private final Counter proofsNotBound;

    public MicrometerMeltMetricsRecorder(MeterRegistry registry) {
        this.insufficientInput = Counter.builder(METRIC_PREFIX + "insufficient_input_total")
                .description("Melt requests rejected because the inputs did not cover invoice + exact fee reserve")
                .register(registry);
        this.proofsNotBound = Counter.builder(METRIC_PREFIX + "proofs_not_bound_total")
                .description("Melt requests failed closed because proofs could not be bound exclusively to the saga")
                .register(registry);
    }

    @Override
    public void insufficientInput() {
        insufficientInput.increment();
    }

    @Override
    public void proofsNotBound() {
        proofsNotBound.increment();
    }
}
