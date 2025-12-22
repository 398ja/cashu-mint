package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TaskMetrics}.
 *
 * Verifies that task execution metrics are correctly recorded.
 */
class TaskMetricsTest {

    private SimpleMeterRegistry registry;
    private TaskMetrics taskMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        taskMetrics = new TaskMetrics(registry);
    }

    @Test
    void getTimer_createsTimerForTask() {
        // When getting a timer for a task
        Timer timer = taskMetrics.getTimer("SwapTask");

        // Then the timer is created
        assertThat(timer).isNotNull();

        // And it has the correct tags
        Timer foundTimer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task", "SwapTask")
                .timer();
        assertThat(foundTimer).isNotNull();
    }

    @Test
    void getTimer_returnsSameTimerForSameTask() {
        // When getting a timer for the same task twice
        Timer timer1 = taskMetrics.getTimer("MintTask");
        Timer timer2 = taskMetrics.getTimer("MintTask");

        // Then the same timer instance is returned
        assertThat(timer1).isSameAs(timer2);
    }

    @Test
    void recordSuccess_incrementsCounter() {
        // When recording a successful task execution
        taskMetrics.recordSuccess("SwapTask");
        taskMetrics.recordSuccess("SwapTask");

        // Then the success counter is incremented
        Counter counter = registry.find("cashu_mint_task_success_total")
                .tag("task", "SwapTask")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordFailure_incrementsCounterWithErrorType() {
        // When recording failed task executions
        taskMetrics.recordFailure("MintTask", "CashuErrorException");
        taskMetrics.recordFailure("MintTask", "CashuErrorException");
        taskMetrics.recordFailure("MintTask", "RuntimeException");

        // Then failure counters are incremented per error type
        Counter cashuErrorCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task", "MintTask")
                .tag("error_type", "CashuErrorException")
                .counter();
        assertThat(cashuErrorCounter).isNotNull();
        assertThat(cashuErrorCounter.count()).isEqualTo(2.0);

        Counter runtimeErrorCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task", "MintTask")
                .tag("error_type", "RuntimeException")
                .counter();
        assertThat(runtimeErrorCounter).isNotNull();
        assertThat(runtimeErrorCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordExecution_recordsDurationAndSuccess() {
        // When recording a successful execution with duration
        taskMetrics.recordExecution("VerifyProofsTask", TimeUnit.MILLISECONDS.toNanos(50), true, null);

        // Then the timer records the duration
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task", "VerifyProofsTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);

        // And the success counter is incremented
        Counter successCounter = registry.find("cashu_mint_task_success_total")
                .tag("task", "VerifyProofsTask")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordExecution_recordsDurationAndFailure() {
        // When recording a failed execution with duration
        taskMetrics.recordExecution("MeltTask", TimeUnit.MILLISECONDS.toNanos(100), false, "InvoiceNotPaidException");

        // Then the timer records the duration
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task", "MeltTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100.0);

        // And the failure counter is incremented
        Counter failureCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task", "MeltTask")
                .tag("error_type", "InvoiceNotPaidException")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordExecution_handlesNullErrorType() {
        // When recording a failed execution with null error type
        taskMetrics.recordExecution("SwapTask", TimeUnit.MILLISECONDS.toNanos(25), false, null);

        // Then the failure counter uses "unknown" as error type
        Counter failureCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task", "SwapTask")
                .tag("error_type", "unknown")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void timerSample_measuresTaskDuration() {
        // When using timer samples
        Timer.Sample sample = taskMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = taskMetrics.stopTimer(sample, "SignBlindedMessageTask");

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(20));

        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task", "SignBlindedMessageTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void normalizeTaskName_extractsSimpleClassName() {
        // When recording with fully qualified class name
        taskMetrics.recordSuccess("xyz.tcheeric.cashu.mint.proto.tasks.SwapTask");

        // Then the counter uses simple class name
        Counter counter = registry.find("cashu_mint_task_success_total")
                .tag("task", "SwapTask")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void normalizeTaskName_handlesNullAndEmpty() {
        // When recording with null or empty task name
        taskMetrics.recordSuccess(null);
        taskMetrics.recordSuccess("");

        // Then "unknown" is used as task name
        Counter counter = registry.find("cashu_mint_task_success_total")
                .tag("task", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void getExecutionCount_returnsCorrectCount() {
        // Given some task executions
        taskMetrics.recordExecution("CheckStateTask", TimeUnit.MILLISECONDS.toNanos(10), true, null);
        taskMetrics.recordExecution("CheckStateTask", TimeUnit.MILLISECONDS.toNanos(15), true, null);
        taskMetrics.recordExecution("CheckStateTask", TimeUnit.MILLISECONDS.toNanos(20), false, "Error");

        // When getting execution count
        double count = taskMetrics.getExecutionCount("CheckStateTask");

        // Then it reflects all executions
        assertThat(count).isEqualTo(3.0);
    }

    @Test
    void getMeanExecutionTime_returnsCorrectMean() {
        // Given task executions with known durations
        taskMetrics.recordExecution("RestoreSignaturesTask", TimeUnit.MILLISECONDS.toNanos(100), true, null);
        taskMetrics.recordExecution("RestoreSignaturesTask", TimeUnit.MILLISECONDS.toNanos(200), true, null);

        // When getting mean execution time
        double mean = taskMetrics.getMeanExecutionTime("RestoreSignaturesTask", TimeUnit.MILLISECONDS);

        // Then it returns the average
        assertThat(mean).isEqualTo(150.0);
    }

    @Test
    void getMaxExecutionTime_returnsCorrectMax() {
        // Given task executions with known durations
        taskMetrics.recordExecution("MintQuoteTask", TimeUnit.MILLISECONDS.toNanos(50), true, null);
        taskMetrics.recordExecution("MintQuoteTask", TimeUnit.MILLISECONDS.toNanos(200), true, null);
        taskMetrics.recordExecution("MintQuoteTask", TimeUnit.MILLISECONDS.toNanos(100), true, null);

        // When getting max execution time
        double max = taskMetrics.getMaxExecutionTime("MintQuoteTask", TimeUnit.MILLISECONDS);

        // Then it returns the maximum
        assertThat(max).isEqualTo(200.0);
    }

    @Test
    void multipleTaskTypes_trackedIndependently() {
        // Given executions of different task types
        taskMetrics.recordSuccess("SwapTask");
        taskMetrics.recordSuccess("SwapTask");
        taskMetrics.recordSuccess("MintTask");
        taskMetrics.recordFailure("MeltTask", "Error");

        // Then each task type has its own metrics
        assertThat(registry.find("cashu_mint_task_success_total")
                .tag("task", "SwapTask").counter().count()).isEqualTo(2.0);
        assertThat(registry.find("cashu_mint_task_success_total")
                .tag("task", "MintTask").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_task_failure_total")
                .tag("task", "MeltTask").counter().count()).isEqualTo(1.0);
    }

    @Test
    void getExecutionCount_returnsZeroForUnknownTask() {
        // When getting execution count for a task that hasn't been recorded
        double count = taskMetrics.getExecutionCount("UnknownTask");

        // Then it returns 0
        assertThat(count).isEqualTo(0.0);
    }

    @Test
    void getMeanExecutionTime_returnsZeroForUnknownTask() {
        // When getting mean for a task that hasn't been recorded
        double mean = taskMetrics.getMeanExecutionTime("UnknownTask", TimeUnit.MILLISECONDS);

        // Then it returns 0
        assertThat(mean).isEqualTo(0.0);
    }
}
