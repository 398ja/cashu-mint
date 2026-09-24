package xyz.tcheeric.cashu.mint.observability.aspect;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import xyz.tcheeric.cashu.mint.observability.metrics.TaskMetrics;

import java.util.Set;

/**
 * AOP aspect for timing Cashu Mint task executions.
 *
 * <p>This aspect automatically instruments all task classes in the
 * {@code xyz.tcheeric.cashu.mint.proto.tasks} package, measuring:
 * <ul>
 *   <li>Execution duration</li>
 *   <li>Success/failure counts</li>
 *   <li>Error types on failure</li>
 * </ul>
 *
 * <p>Can be disabled by setting {@code cashu.observability.tasks.enabled=false}.
 *
 * <p>Instrumented tasks include:
 * <ul>
 *   <li>SwapTask - Token swapping</li>
 *   <li>MintTask - Token minting</li>
 *   <li>MintTokensTask - Token minting (alternative)</li>
 *   <li>MeltTask - Token melting</li>
 *   <li>MeltTokensTask - Token melting (alternative)</li>
 *   <li>SignBlindedMessageTask - Blind signature generation</li>
 *   <li>VerifyProofsTask - Proof verification</li>
 *   <li>VerifyFeesTask - Fee verification</li>
 *   <li>CheckStateTask - Proof state checking</li>
 *   <li>RestoreSignaturesTask - Signature restoration</li>
 *   <li>MintQuoteTask - Mint quote creation</li>
 *   <li>MeltQuoteTask - Melt quote creation</li>
 *   <li>VoucherMintQuoteTask - Voucher mint quote creation</li>
 *   <li>And other tasks in the tasks package</li>
 * </ul>
 */
@Aspect
@Slf4j
public class TaskTimingAspect {

    /**
     * Exception types that are an ANSWER rather than a fault.
     *
     * <p>Matched on simple name because these are thrown by other modules
     * (InvoiceNotPaidException lives in payment-adapter) and this module does
     * not depend on them. A name is a weaker contract than a type, so the set
     * is kept small and each entry has to earn its place by being a normal
     * outcome of a healthy request.
     */
    private static final Set<String> EXPECTED_OUTCOMES = Set.of("InvoiceNotPaidException");

    private final TaskMetrics taskMetrics;

    /**
     * Creates a new TaskTimingAspect.
     *
     * @param taskMetrics the task metrics instance for recording
     */
    public TaskTimingAspect(TaskMetrics taskMetrics) {
        this.taskMetrics = taskMetrics;
        log.info("TaskTimingAspect initialized - task instrumentation enabled");
    }

    /**
     * Pointcut matching all execute() methods in task classes.
     *
     * <p>Matches any class in the {@code xyz.tcheeric.cashu.mint.proto.tasks} package
     * that has an execute() method (typical for Task implementations).
     */
    @Pointcut("execution(* xyz.tcheeric.cashu.mint.proto.tasks.*.execute(..))")
    public void taskExecuteMethod() {
        // Pointcut definition - no body needed
    }

    /**
     * Around advice that times task execution.
     *
     * <p>Records:
     * <ul>
     *   <li>Task name (derived from class name)</li>
     *   <li>Execution duration</li>
     *   <li>Success or failure outcome</li>
     *   <li>Error type if failed</li>
     * </ul>
     *
     * @param pjp the join point for the intercepted method
     * @return the result of the task execution
     * @throws Throwable if the task throws an exception
     */
    @Around("taskExecuteMethod()")
    public Object timeTaskExecution(ProceedingJoinPoint pjp) throws Throwable {
        String taskName = pjp.getTarget().getClass().getSimpleName();
        Timer.Sample sample = taskMetrics.startTimer();

        if (log.isTraceEnabled()) {
            log.trace("Starting task: {}", taskName);
        }

        try {
            Object result = pjp.proceed();

            // Record successful execution
            long duration = sample.stop(taskMetrics.getTimer(taskName));
            taskMetrics.recordSuccess(taskName);

            if (log.isTraceEnabled()) {
                log.trace("Task {} completed successfully in {} ms", taskName, duration / 1_000_000);
            }

            return result;
        } catch (Throwable e) {
            long duration = sample.stop(taskMetrics.getTimer(taskName));
            String errorType = e.getClass().getSimpleName();

            // AN EXPECTED ANSWER IS NOT A FAILURE.
            //
            // Some tasks answer a question whose negative answer is normal.
            // "Has this invoice been paid yet" throws InvoiceNotPaidException
            // before settlement, and every client polls until it flips, so a
            // healthy sale produces several by design.
            //
            // Measured on staging: 11 failures against 10 successes on a run
            // where every sale SUCCEEDED, which held CashuMintTaskFailureRate
            // permanently over threshold. An alert that fires whenever the
            // system works correctly is one that gets muted, and a muted alert
            // cannot report the fault it exists for.
            //
            // Still counted, under its own metric, because how often clients
            // poll early is useful. It simply is not a failure.
            if (EXPECTED_OUTCOMES.contains(errorType)) {
                taskMetrics.recordExpectedOutcome(taskName, errorType);
                if (log.isTraceEnabled()) {
                    log.trace("Task {} returned expected outcome {} after {} ms",
                            taskName, errorType, duration / 1_000_000);
                }
                throw e;
            }

            taskMetrics.recordFailure(taskName, errorType);

            if (log.isDebugEnabled()) {
                log.debug("Task {} failed after {} ms with {}: {}",
                        taskName, duration / 1_000_000, errorType, e.getMessage());
            }

            throw e;
        }
    }
}
