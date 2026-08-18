package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.metrics.MeltMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the melt recorder port maps onto the mandated Micrometer counter names. */
class MicrometerMeltMetricsRecorderTest {

    /** The family must exist before the first rejection, or dashboards read "no data". */
    @Test
    void registersBothCountersEagerlyAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MicrometerMeltMetricsRecorder(registry);

        assertThat(registry.find("cashu_mint_melt_insufficient_input_total").counter()).isNotNull();
        assertThat(registry.find("cashu_mint_melt_proofs_not_bound_total").counter()).isNotNull();
        assertThat(registry.get("cashu_mint_melt_insufficient_input_total").counter().count()).isZero();
    }

    /** Each port method increments its own counter and no other. */
    @Test
    void eachMethodIncrementsItsOwnCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeltMetricsRecorder recorder = new MicrometerMeltMetricsRecorder(registry);

        recorder.insufficientInput();
        recorder.insufficientInput();
        recorder.proofsNotBound();

        assertThat(registry.get("cashu_mint_melt_insufficient_input_total").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cashu_mint_melt_proofs_not_bound_total").counter().count()).isEqualTo(1.0);
    }

    /** Unwired contexts must record into a no-op rather than NPE at the call site. */
    @Test
    void unregisteredMeltRecorderIsANoOp() {
        MeltMetricsRecorder previous = MetricRecorders.melt();
        try {
            MetricRecorders.registerMelt(null);

            assertThat(MetricRecorders.melt()).isNotNull();
            MetricRecorders.melt().insufficientInput();
            MetricRecorders.melt().proofsNotBound();
        } finally {
            MetricRecorders.registerMelt(previous);
        }
    }

    /** Registration swaps the no-op for the live recorder. */
    @Test
    void registeredRecorderReceivesCallsThroughTheStaticAccessor() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            MetricRecorders.registerMelt(new MicrometerMeltMetricsRecorder(registry));

            MetricRecorders.melt().insufficientInput();

            assertThat(registry.get("cashu_mint_melt_insufficient_input_total").counter().count()).isEqualTo(1.0);
        } finally {
            MetricRecorders.registerMelt(null);
        }
    }
}
