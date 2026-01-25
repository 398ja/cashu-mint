package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LockMetrics}.
 */
class LockMetricsTest {

    private MeterRegistry registry;
    private LockMetrics lockMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        lockMetrics = new LockMetrics(registry);
    }

    @Test
    void recordLockWait_createsTimerAndRecords() {
        // Given a lock type and wait time
        String lockType = "quote";
        long waitTimeNanos = 1_000_000L; // 1ms

        // When recording lock wait
        lockMetrics.recordLockWait(lockType, waitTimeNanos);

        // Then timer should be created and recorded
        Timer timer = registry.find("cashu_mint_lock_wait_seconds")
                .tag("lock_type", lockType)
                .timer();

        assertNotNull(timer, "Wait timer should be registered");
        assertEquals(1, timer.count(), "Timer should have 1 recording");
        assertEquals(1.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.01,
                "Total time should be ~1ms");
    }

    @Test
    void recordLockHold_createsTimerAndRecords() {
        // Given a lock type and hold time
        String lockType = "proof";
        long holdTimeNanos = 5_000_000L; // 5ms

        // When recording lock hold
        lockMetrics.recordLockHold(lockType, holdTimeNanos);

        // Then timer should be created and recorded
        Timer timer = registry.find("cashu_mint_lock_hold_seconds")
                .tag("lock_type", lockType)
                .timer();

        assertNotNull(timer, "Hold timer should be registered");
        assertEquals(1, timer.count(), "Timer should have 1 recording");
        assertEquals(5.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.01,
                "Total time should be ~5ms");
    }

    @Test
    void updateActiveLockCount_createsGaugeAndUpdates() {
        // Given a lock type
        String lockType = "quote";

        // When updating active lock count
        lockMetrics.updateActiveLockCount(lockType, 5);

        // Then gauge should reflect the count
        assertEquals(5, lockMetrics.getActiveLockCount(lockType));

        // When updating again
        lockMetrics.updateActiveLockCount(lockType, 3);

        // Then gauge should reflect new count
        assertEquals(3, lockMetrics.getActiveLockCount(lockType));
    }

    @Test
    void multipleRecordings_accumulateCorrectly() {
        // Given multiple wait time recordings
        String lockType = "quote";
        lockMetrics.recordLockWait(lockType, 1_000_000L); // 1ms
        lockMetrics.recordLockWait(lockType, 2_000_000L); // 2ms
        lockMetrics.recordLockWait(lockType, 3_000_000L); // 3ms

        // Then mean should be average
        double mean = lockMetrics.getMeanWaitTime(lockType, TimeUnit.MILLISECONDS);
        assertEquals(2.0, mean, 0.01, "Mean should be ~2ms");

        // And max should be highest
        double max = lockMetrics.getMaxWaitTime(lockType, TimeUnit.MILLISECONDS);
        assertEquals(3.0, max, 0.01, "Max should be ~3ms");
    }

    @Test
    void differentLockTypes_areTrackedSeparately() {
        // Given recordings for different lock types
        lockMetrics.recordLockWait("quote", 1_000_000L);
        lockMetrics.recordLockWait("proof", 2_000_000L);
        lockMetrics.updateActiveLockCount("quote", 5);
        lockMetrics.updateActiveLockCount("proof", 10);

        // Then each type should have separate metrics
        Timer quoteTimer = registry.find("cashu_mint_lock_wait_seconds")
                .tag("lock_type", "quote")
                .timer();
        Timer proofTimer = registry.find("cashu_mint_lock_wait_seconds")
                .tag("lock_type", "proof")
                .timer();

        assertNotNull(quoteTimer);
        assertNotNull(proofTimer);
        assertEquals(1, quoteTimer.count());
        assertEquals(1, proofTimer.count());
        assertEquals(5, lockMetrics.getActiveLockCount("quote"));
        assertEquals(10, lockMetrics.getActiveLockCount("proof"));
    }

    @Test
    void getActiveLockCount_returnsZeroForUnknownType() {
        // Given no recordings for a lock type
        // When querying active count
        int count = lockMetrics.getActiveLockCount("unknown");

        // Then should return 0
        assertEquals(0, count);
    }

    @Test
    void getMeanWaitTime_returnsZeroForUnknownType() {
        // Given no recordings for a lock type
        // When querying mean wait time
        double mean = lockMetrics.getMeanWaitTime("unknown", TimeUnit.MILLISECONDS);

        // Then should return 0
        assertEquals(0, mean);
    }
}
