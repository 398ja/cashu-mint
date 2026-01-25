package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics for lock operations in the Cashu Mint protocol.
 *
 * <p>This class provides metrics instrumentation for per-quote and per-proof locks
 * used to prevent double-mint and double-spend attacks:
 * <ul>
 *   <li>Lock wait time (time spent waiting to acquire a lock)</li>
 *   <li>Lock hold time (time spent holding a lock)</li>
 *   <li>Active lock count (current number of held locks)</li>
 * </ul>
 *
 * <p>These metrics are essential for monitoring virtual thread performance:
 * <ul>
 *   <li>High lock wait times may indicate contention issues</li>
 *   <li>Long hold times may indicate slow downstream operations</li>
 *   <li>Growing active lock counts may indicate lock leaks</li>
 * </ul>
 *
 * <p>All metrics follow the naming convention: {@code cashu_mint_lock_*}
 */
@Slf4j
public class LockMetrics {

    private static final String METRIC_PREFIX = "cashu_mint_lock_";

    private final MeterRegistry registry;

    // Cached timers by lock type
    private final ConcurrentHashMap<String, Timer> waitTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> holdTimers = new ConcurrentHashMap<>();

    // Gauges for active lock counts
    private final ConcurrentHashMap<String, AtomicInteger> activeLockCounts = new ConcurrentHashMap<>();

    /**
     * Creates a new LockMetrics instance.
     *
     * @param registry the Micrometer registry to use
     */
    public LockMetrics(MeterRegistry registry) {
        this.registry = registry;
        log.debug("LockMetrics initialized");
    }

    /**
     * Records the time spent waiting to acquire a lock.
     *
     * @param lockType      the type of lock (e.g., "quote", "proof")
     * @param waitTimeNanos time spent waiting in nanoseconds
     */
    public void recordLockWait(String lockType, long waitTimeNanos) {
        getWaitTimer(lockType).record(waitTimeNanos, TimeUnit.NANOSECONDS);
        log.trace("Lock wait recorded: type={}, waitTime={}ns", lockType, waitTimeNanos);
    }

    /**
     * Records the time spent holding a lock.
     *
     * @param lockType      the type of lock (e.g., "quote", "proof")
     * @param holdTimeNanos time spent holding the lock in nanoseconds
     */
    public void recordLockHold(String lockType, long holdTimeNanos) {
        getHoldTimer(lockType).record(holdTimeNanos, TimeUnit.NANOSECONDS);
        log.trace("Lock hold recorded: type={}, holdTime={}ns", lockType, holdTimeNanos);
    }

    /**
     * Updates the gauge for active lock count.
     *
     * @param lockType    the type of lock (e.g., "quote", "proof")
     * @param activeCount current number of active locks
     */
    public void updateActiveLockCount(String lockType, int activeCount) {
        AtomicInteger counter = getOrCreateActiveLockCounter(lockType);
        counter.set(activeCount);
        log.trace("Active lock count updated: type={}, count={}", lockType, activeCount);
    }

    /**
     * Gets the current active lock count for a lock type.
     *
     * @param lockType the type of lock
     * @return current active lock count
     */
    public int getActiveLockCount(String lockType) {
        AtomicInteger counter = activeLockCounts.get(lockType);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Gets the mean wait time for a lock type.
     *
     * @param lockType the type of lock
     * @param timeUnit the time unit for the result
     * @return mean wait time
     */
    public double getMeanWaitTime(String lockType, TimeUnit timeUnit) {
        Timer timer = waitTimers.get(lockType);
        return timer != null ? timer.mean(timeUnit) : 0;
    }

    /**
     * Gets the maximum wait time for a lock type.
     *
     * @param lockType the type of lock
     * @param timeUnit the time unit for the result
     * @return maximum wait time
     */
    public double getMaxWaitTime(String lockType, TimeUnit timeUnit) {
        Timer timer = waitTimers.get(lockType);
        return timer != null ? timer.max(timeUnit) : 0;
    }

    /**
     * Gets the mean hold time for a lock type.
     *
     * @param lockType the type of lock
     * @param timeUnit the time unit for the result
     * @return mean hold time
     */
    public double getMeanHoldTime(String lockType, TimeUnit timeUnit) {
        Timer timer = holdTimers.get(lockType);
        return timer != null ? timer.mean(timeUnit) : 0;
    }

    // Helper methods

    private Timer getWaitTimer(String lockType) {
        return waitTimers.computeIfAbsent(lockType, type ->
                Timer.builder(METRIC_PREFIX + "wait_seconds")
                        .description("Time spent waiting to acquire a lock")
                        .tag("lock_type", type)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    private Timer getHoldTimer(String lockType) {
        return holdTimers.computeIfAbsent(lockType, type ->
                Timer.builder(METRIC_PREFIX + "hold_seconds")
                        .description("Time spent holding a lock")
                        .tag("lock_type", type)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    private AtomicInteger getOrCreateActiveLockCounter(String lockType) {
        return activeLockCounts.computeIfAbsent(lockType, type -> {
            AtomicInteger counter = new AtomicInteger(0);
            Gauge.builder(METRIC_PREFIX + "active", counter, AtomicInteger::get)
                    .description("Number of currently held locks")
                    .tag("lock_type", type)
                    .register(registry);
            return counter;
        });
    }
}
