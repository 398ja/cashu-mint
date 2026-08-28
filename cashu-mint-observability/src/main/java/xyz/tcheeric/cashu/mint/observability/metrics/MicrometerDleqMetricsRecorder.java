package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.mint.proto.metrics.DleqMetricsRecorder;

/**
 * Micrometer implementation of the NUT-12 DLEQ recorder port (issue #389).
 *
 * <p>The counter is registered eagerly so it reads 0 rather than missing while
 * proof generation is healthy, which is what makes an alert on it armable.
 */
public class MicrometerDleqMetricsRecorder implements DleqMetricsRecorder {

    private final Counter generationFailures;

    public MicrometerDleqMetricsRecorder(MeterRegistry registry) {
        this.generationFailures = Counter.builder("cashu_mint_dleq_generation_failures_total")
                .description("NUT-12 DLEQ proof generations that failed, failing the signing request")
                .register(registry);
    }

    @Override
    public void generationFailed() {
        generationFailures.increment();
    }
}
