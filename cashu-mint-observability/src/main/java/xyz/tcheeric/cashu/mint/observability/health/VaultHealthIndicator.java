package xyz.tcheeric.cashu.mint.observability.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health indicator for Cashu Vault connectivity.
 *
 * <p>This indicator tracks the health status of the vault service
 * which manages proof storage, signatures, and keyset data.
 *
 * <p>Health status is determined by:
 * <ul>
 *   <li>Last successful vault operation time</li>
 *   <li>Recent error count</li>
 *   <li>Database connectivity status</li>
 * </ul>
 */
@Slf4j
public class VaultHealthIndicator implements HealthIndicator {

    private static final long STALE_THRESHOLD_MS = 60_000; // 1 minute

    private final AtomicBoolean healthy;
    private final AtomicLong lastSuccessTime;
    private final AtomicLong lastErrorTime;
    private final AtomicReference<String> lastErrorMessage;
    private final AtomicLong successCount;
    private final AtomicLong errorCount;
    private final AtomicReference<String> connectionStatus;
    private final long timeoutMs;

    /**
     * Creates a new VaultHealthIndicator.
     *
     * @param timeoutMs timeout for considering the vault as potentially unhealthy
     */
    public VaultHealthIndicator(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.healthy = new AtomicBoolean(true);
        this.lastSuccessTime = new AtomicLong(System.currentTimeMillis());
        this.lastErrorTime = new AtomicLong(0);
        this.lastErrorMessage = new AtomicReference<>("");
        this.successCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.connectionStatus = new AtomicReference<>("initialized");
        log.debug("VaultHealthIndicator initialized with timeout={}ms", timeoutMs);
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        long timeSinceLastSuccess = now - lastSuccessTime.get();
        long timeSinceLastError = now - lastErrorTime.get();

        Health.Builder builder;

        if (!healthy.get()) {
            builder = Health.down();
            builder.withDetail("status", "unhealthy");
        } else if (timeSinceLastSuccess > STALE_THRESHOLD_MS && lastErrorTime.get() > lastSuccessTime.get()) {
            builder = Health.down();
            builder.withDetail("status", "no_recent_success");
        } else if (timeSinceLastSuccess > timeoutMs) {
            builder = Health.unknown();
            builder.withDetail("status", "stale");
        } else {
            builder = Health.up();
            builder.withDetail("status", "connected");
        }

        builder.withDetail("connection", connectionStatus.get());
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
     * Records a successful vault operation.
     */
    public void recordSuccess() {
        lastSuccessTime.set(System.currentTimeMillis());
        successCount.incrementAndGet();
        healthy.set(true);
        connectionStatus.set("connected");
        log.trace("Vault health: success recorded");
    }

    /**
     * Records a failed vault operation.
     *
     * @param errorMessage description of the error
     */
    public void recordError(String errorMessage) {
        lastErrorTime.set(System.currentTimeMillis());
        lastErrorMessage.set(errorMessage != null ? errorMessage : "unknown");
        errorCount.incrementAndGet();
        log.trace("Vault health: error recorded - {}", errorMessage);
    }

    /**
     * Records a database connectivity error.
     *
     * @param errorMessage description of the connection error
     */
    public void recordConnectionError(String errorMessage) {
        healthy.set(false);
        lastErrorTime.set(System.currentTimeMillis());
        lastErrorMessage.set(errorMessage != null ? errorMessage : "connection_failed");
        connectionStatus.set("disconnected");
        errorCount.incrementAndGet();
        log.warn("Vault connection error: {}", errorMessage);
    }

    /**
     * Marks the vault as unhealthy.
     *
     * @param reason the reason for marking unhealthy
     */
    public void markUnhealthy(String reason) {
        healthy.set(false);
        lastErrorTime.set(System.currentTimeMillis());
        lastErrorMessage.set(reason != null ? reason : "marked_unhealthy");
        log.warn("Vault marked as unhealthy: {}", reason);
    }

    /**
     * Marks the vault as healthy.
     */
    public void markHealthy() {
        healthy.set(true);
        lastSuccessTime.set(System.currentTimeMillis());
        connectionStatus.set("connected");
        log.info("Vault marked as healthy");
    }

    /**
     * Updates the connection status.
     *
     * @param status the connection status
     */
    public void setConnectionStatus(String status) {
        connectionStatus.set(status != null ? status : "unknown");
    }

    /**
     * Gets whether the vault is currently considered healthy.
     *
     * @return true if healthy
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Gets the current connection status.
     *
     * @return connection status string
     */
    public String getConnectionStatus() {
        return connectionStatus.get();
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
        connectionStatus.set("initialized");
        log.debug("Vault health indicator reset");
    }
}
