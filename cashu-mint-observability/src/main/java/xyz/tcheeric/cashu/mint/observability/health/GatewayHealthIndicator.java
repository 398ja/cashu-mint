package xyz.tcheeric.cashu.mint.observability.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health indicator for Lightning gateway connectivity.
 *
 * <p>This indicator tracks the health status of the configured payment gateway
 * (e.g., Phoenixd, LND). The health status can be updated externally by gateway
 * clients or through periodic health checks.
 *
 * <p>Health status is determined by:
 * <ul>
 *   <li>Last successful gateway response time</li>
 *   <li>Recent error count</li>
 *   <li>Manual status updates from gateway operations</li>
 * </ul>
 */
@Slf4j
public class GatewayHealthIndicator implements HealthIndicator {

    private static final long STALE_THRESHOLD_MS = 60_000; // 1 minute

    private final AtomicBoolean healthy;
    private final AtomicLong lastSuccessTime;
    private final AtomicLong lastErrorTime;
    private final AtomicReference<String> lastErrorMessage;
    private final AtomicLong successCount;
    private final AtomicLong errorCount;
    private final long timeoutMs;

    /**
     * Creates a new GatewayHealthIndicator.
     *
     * @param timeoutMs timeout for considering the gateway as potentially unhealthy
     */
    public GatewayHealthIndicator(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.healthy = new AtomicBoolean(true);
        this.lastSuccessTime = new AtomicLong(System.currentTimeMillis());
        this.lastErrorTime = new AtomicLong(0);
        this.lastErrorMessage = new AtomicReference<>("");
        this.successCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        log.debug("GatewayHealthIndicator initialized with timeout={}ms", timeoutMs);
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        long timeSinceLastSuccess = now - lastSuccessTime.get();
        long timeSinceLastError = now - lastErrorTime.get();

        Health.Builder builder;
        boolean hasObservedSuccess = successCount.get() > 0;

        if (!healthy.get()) {
            builder = Health.down();
            builder.withDetail("status", "unhealthy");
        } else if (timeSinceLastSuccess > STALE_THRESHOLD_MS && lastErrorTime.get() > lastSuccessTime.get()) {
            builder = Health.down();
            builder.withDetail("status", "no_recent_success");
        } else if (hasObservedSuccess && timeSinceLastSuccess > timeoutMs) {
            // Avoid flipping to stale when we have never seen a gateway response; wait for real data first.
            builder = Health.unknown();
            builder.withDetail("status", "stale");
        } else {
            builder = Health.up();
            builder.withDetail("status", "connected");
        }

        builder.withDetail("lastSuccessMs", timeSinceLastSuccess);
        builder.withDetail("successCount", successCount.get());
        builder.withDetail("errorCount", errorCount.get());

        if (lastErrorTime.get() > 0) {
            builder.withDetail("lastErrorMs", timeSinceLastError);
            String errorMsg = lastErrorMessage.get();
            if (errorMsg != null && !errorMsg.isEmpty()) {
                builder.withDetail("lastError", errorMsg);
            }
        }

        return builder.build();
    }

    /**
     * Records a successful gateway operation.
     */
    public void recordSuccess() {
        lastSuccessTime.set(System.currentTimeMillis());
        successCount.incrementAndGet();
        healthy.set(true);
        log.trace("Gateway health: success recorded");
    }

    /**
     * Records a failed gateway operation.
     *
     * @param errorMessage description of the error
     */
    public void recordError(String errorMessage) {
        lastErrorTime.set(System.currentTimeMillis());
        lastErrorMessage.set(errorMessage != null ? errorMessage : "unknown");
        errorCount.incrementAndGet();
        log.trace("Gateway health: error recorded - {}", errorMessage);
    }

    /**
     * Marks the gateway as unhealthy.
     *
     * @param reason the reason for marking unhealthy
     */
    public void markUnhealthy(String reason) {
        healthy.set(false);
        lastErrorTime.set(System.currentTimeMillis());
        lastErrorMessage.set(reason != null ? reason : "marked_unhealthy");
        log.warn("Gateway marked as unhealthy: {}", reason);
    }

    /**
     * Marks the gateway as healthy.
     */
    public void markHealthy() {
        healthy.set(true);
        lastSuccessTime.set(System.currentTimeMillis());
        log.info("Gateway marked as healthy");
    }

    /**
     * Gets whether the gateway is currently considered healthy.
     *
     * @return true if healthy
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Gets the total success count.
     *
     * @return success count
     */
    public long getSuccessCount() {
        return successCount.get();
    }

    /**
     * Gets the total error count.
     *
     * @return error count
     */
    public long getErrorCount() {
        return errorCount.get();
    }

    /**
     * Resets the health indicator state.
     */
    public void reset() {
        healthy.set(true);
        lastSuccessTime.set(System.currentTimeMillis());
        lastErrorTime.set(0);
        lastErrorMessage.set("");
        successCount.set(0);
        errorCount.set(0);
        log.debug("Gateway health indicator reset");
    }
}
