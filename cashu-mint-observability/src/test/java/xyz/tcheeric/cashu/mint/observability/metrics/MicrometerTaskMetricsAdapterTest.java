package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.metrics.TaskExecutionRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerTaskMetricsAdapterTest {

    @AfterEach
    void tearDown() {
        TaskExecutionRecorder.register(null);
    }

    // Ensures successful task execution is recorded through the adapter
    @Test
    void recordSuccessUpdatesMetrics() throws CashuErrorException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskMetrics metrics = new TaskMetrics(registry);
        TaskExecutionRecorder.register(new MicrometerTaskMetricsAdapter(metrics));

        String result = TaskExecutionRecorder.record("SampleTask", () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(metrics.getExecutionCount("SampleTask")).isEqualTo(1);
    }

    // Ensures failed task execution is recorded with the failure counter
    @Test
    void recordFailureUpdatesMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskMetrics metrics = new TaskMetrics(registry);
        TaskExecutionRecorder.register(new MicrometerTaskMetricsAdapter(metrics));

        assertThatThrownBy(() -> TaskExecutionRecorder.record("FailingTask", () -> {
            throw new CashuErrorException("boom");
        })).isInstanceOf(CashuErrorException.class);

        Counter failureCounter = registry.find("cashu_mint_task_failure_total").counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1);
    }
}
