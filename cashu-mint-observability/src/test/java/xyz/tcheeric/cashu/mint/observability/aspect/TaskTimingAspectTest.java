package xyz.tcheeric.cashu.mint.observability.aspect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.mint.observability.metrics.TaskMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskTimingAspect}.
 *
 * Verifies that the aspect correctly times task executions and records
 * success/failure metrics.
 */
@ExtendWith(MockitoExtension.class)
class TaskTimingAspectTest {

    private SimpleMeterRegistry registry;
    private TaskMetrics taskMetrics;
    private TaskTimingAspect aspect;

    @Mock
    private ProceedingJoinPoint joinPoint;

    // Mock target object to simulate a task
    private final Object mockSwapTask = new MockSwapTask();
    private final Object mockMintTask = new MockMintTask();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        taskMetrics = new TaskMetrics(registry);
        aspect = new TaskTimingAspect(taskMetrics);
    }

    @Test
    void timeTaskExecution_recordsSuccessfulExecution() throws Throwable {
        // Given a successful task execution
        when(joinPoint.getTarget()).thenReturn(mockSwapTask);
        when(joinPoint.proceed()).thenReturn("success");

        // When the aspect intercepts the execution
        Object result = aspect.timeTaskExecution(joinPoint);

        // Then the result is returned
        assertThat(result).isEqualTo("success");

        // And the timer is recorded
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockSwapTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        // And success is recorded
        Counter successCounter = registry.find("cashu_mint_task_success_total")
                .tag("task_name", "MockSwapTask")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    @Test
    void timeTaskExecution_recordsFailedExecution() throws Throwable {
        // Given a task that throws an exception
        RuntimeException exception = new RuntimeException("Test error");
        when(joinPoint.getTarget()).thenReturn(mockMintTask);
        when(joinPoint.proceed()).thenThrow(exception);

        // When the aspect intercepts the execution
        assertThatThrownBy(() -> aspect.timeTaskExecution(joinPoint))
                .isSameAs(exception);

        // Then the timer is still recorded
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockMintTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        // And failure is recorded with error type
        Counter failureCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task_name", "MockMintTask")
                .tag("error_type", "RuntimeException")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void timeTaskExecution_handlesCheckedExceptions() throws Throwable {
        // Given a task that throws a checked exception
        Exception checkedException = new Exception("Checked error");
        when(joinPoint.getTarget()).thenReturn(mockSwapTask);
        when(joinPoint.proceed()).thenThrow(checkedException);

        // When the aspect intercepts the execution
        assertThatThrownBy(() -> aspect.timeTaskExecution(joinPoint))
                .isSameAs(checkedException);

        // Then failure is recorded with the exception type
        Counter failureCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task_name", "MockSwapTask")
                .tag("error_type", "Exception")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void timeTaskExecution_multipleExecutions_aggregatesMetrics() throws Throwable {
        // Given multiple successful executions
        when(joinPoint.getTarget()).thenReturn(mockSwapTask);
        when(joinPoint.proceed()).thenReturn("result1", "result2", "result3");

        // When executing multiple times
        aspect.timeTaskExecution(joinPoint);
        aspect.timeTaskExecution(joinPoint);
        aspect.timeTaskExecution(joinPoint);

        // Then the timer count reflects all executions
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockSwapTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3);

        // And success counter reflects all executions
        Counter successCounter = registry.find("cashu_mint_task_success_total")
                .tag("task_name", "MockSwapTask")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(3.0);
    }

    @Test
    void timeTaskExecution_mixedSuccessAndFailure() throws Throwable {
        // Given a mix of successful and failed executions
        when(joinPoint.getTarget()).thenReturn(mockSwapTask);
        when(joinPoint.proceed())
                .thenReturn("success")
                .thenThrow(new IllegalStateException("error"))
                .thenReturn("success");

        // When executing
        aspect.timeTaskExecution(joinPoint);
        assertThatThrownBy(() -> aspect.timeTaskExecution(joinPoint))
                .isInstanceOf(IllegalStateException.class);
        aspect.timeTaskExecution(joinPoint);

        // Then timer reflects all executions
        Timer timer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockSwapTask")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3);

        // And success counter reflects successful executions
        Counter successCounter = registry.find("cashu_mint_task_success_total")
                .tag("task_name", "MockSwapTask")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(2.0);

        // And failure counter reflects failed executions
        Counter failureCounter = registry.find("cashu_mint_task_failure_total")
                .tag("task_name", "MockSwapTask")
                .tag("error_type", "IllegalStateException")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void timeTaskExecution_differentTaskTypes_trackedSeparately() throws Throwable {
        // Given different task types
        when(joinPoint.getTarget()).thenReturn(mockSwapTask, mockMintTask);
        when(joinPoint.proceed()).thenReturn("result");

        // When executing different tasks
        aspect.timeTaskExecution(joinPoint);
        when(joinPoint.getTarget()).thenReturn(mockMintTask);
        aspect.timeTaskExecution(joinPoint);

        // Then each task type has its own timer
        Timer swapTimer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockSwapTask")
                .timer();
        assertThat(swapTimer).isNotNull();
        assertThat(swapTimer.count()).isEqualTo(1);

        Timer mintTimer = registry.find("cashu_mint_task_duration_seconds")
                .tag("task_name", "MockMintTask")
                .timer();
        assertThat(mintTimer).isNotNull();
        assertThat(mintTimer.count()).isEqualTo(1);
    }

    @Test
    void timeTaskExecution_returnsNullResult() throws Throwable {
        // Given a task that returns null
        when(joinPoint.getTarget()).thenReturn(mockSwapTask);
        when(joinPoint.proceed()).thenReturn(null);

        // When the aspect intercepts the execution
        Object result = aspect.timeTaskExecution(joinPoint);

        // Then null is returned
        assertThat(result).isNull();

        // And success is still recorded
        Counter successCounter = registry.find("cashu_mint_task_success_total")
                .tag("task_name", "MockSwapTask")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    // Mock task classes for testing
    private static class MockSwapTask {
        public Object execute() {
            return "swap result";
        }
    }

    private static class MockMintTask {
        public Object execute() {
            return "mint result";
        }
    }
}
