package xyz.tcheeric.cashu.mint.observability.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VaultHealthIndicator}.
 *
 * Verifies health status determination based on vault connectivity.
 */
class VaultHealthIndicatorTest {

    private VaultHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new VaultHealthIndicator(5000);
    }

    @Test
    void health_initialState_returnsUp() {
        // When checking health immediately after initialization
        Health health = healthIndicator.health();

        // Then status is UP
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "connected");
        assertThat(health.getDetails()).containsEntry("connection", "initialized");
    }

    @Test
    void health_afterRecordSuccess_returnsUp() {
        // When recording success
        healthIndicator.recordSuccess();

        // Then status is UP with connected status
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("connection", "connected");
        assertThat(health.getDetails()).containsEntry("successCount", 1L);
    }

    @Test
    void health_afterMarkUnhealthy_returnsDown() {
        // When marking unhealthy
        healthIndicator.markUnhealthy("Database connection lost");

        // Then status is DOWN
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "unhealthy");
        assertThat(health.getDetails()).containsEntry("lastError", "Database connection lost");
    }

    @Test
    void health_afterMarkHealthy_returnsUp() {
        // Given unhealthy state
        healthIndicator.markUnhealthy("error");

        // When marking healthy
        healthIndicator.markHealthy();

        // Then status is UP
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("connection", "connected");
    }

    @Test
    void recordError_incrementsErrorCount() {
        // When recording errors
        healthIndicator.recordError("query timeout");
        healthIndicator.recordError("constraint violation");

        // Then error count is incremented
        assertThat(healthIndicator.getErrorCount()).isEqualTo(2);

        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("errorCount", 2L);
        assertThat(health.getDetails()).containsEntry("lastError", "constraint violation");
    }

    @Test
    void recordConnectionError_marksUnhealthy() {
        // When recording connection error
        healthIndicator.recordConnectionError("Connection pool exhausted");

        // Then status is DOWN and connection shows disconnected
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("connection", "disconnected");
        assertThat(health.getDetails()).containsEntry("lastError", "Connection pool exhausted");
        assertThat(healthIndicator.isHealthy()).isFalse();
    }

    @Test
    void recordSuccess_incrementsSuccessCount() {
        // When recording successes
        healthIndicator.recordSuccess();
        healthIndicator.recordSuccess();
        healthIndicator.recordSuccess();

        // Then success count is incremented
        assertThat(healthIndicator.getSuccessCount()).isEqualTo(3);

        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("successCount", 3L);
    }

    @Test
    void isHealthy_reflectsCurrentState() {
        // Initially healthy
        assertThat(healthIndicator.isHealthy()).isTrue();

        // After marking unhealthy
        healthIndicator.markUnhealthy("error");
        assertThat(healthIndicator.isHealthy()).isFalse();

        // After marking healthy again
        healthIndicator.markHealthy();
        assertThat(healthIndicator.isHealthy()).isTrue();
    }

    @Test
    void setConnectionStatus_updatesStatus() {
        // When setting connection status
        healthIndicator.setConnectionStatus("reconnecting");

        // Then status is updated
        assertThat(healthIndicator.getConnectionStatus()).isEqualTo("reconnecting");

        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("connection", "reconnecting");
    }

    @Test
    void setConnectionStatus_withNull_setsUnknown() {
        // When setting null connection status
        healthIndicator.setConnectionStatus(null);

        // Then status is "unknown"
        assertThat(healthIndicator.getConnectionStatus()).isEqualTo("unknown");
    }

    @Test
    void reset_clearsAllState() {
        // Given some recorded state
        healthIndicator.recordSuccess();
        healthIndicator.recordError("error");
        healthIndicator.markUnhealthy("reason");
        healthIndicator.setConnectionStatus("disconnected");

        // When resetting
        healthIndicator.reset();

        // Then all state is cleared
        assertThat(healthIndicator.isHealthy()).isTrue();
        assertThat(healthIndicator.getSuccessCount()).isEqualTo(0);
        assertThat(healthIndicator.getErrorCount()).isEqualTo(0);
        assertThat(healthIndicator.getConnectionStatus()).isEqualTo("initialized");

        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_includesTimingDetails() {
        // When checking health
        Health health = healthIndicator.health();

        // Then timing details are included
        assertThat(health.getDetails()).containsKey("lastSuccessMs");
        assertThat(health.getDetails()).containsKey("successCount");
        assertThat(health.getDetails()).containsKey("errorCount");
        assertThat(health.getDetails()).containsKey("connection");
    }

    @Test
    void health_afterError_includesLastErrorMs() {
        // Given an error was recorded
        healthIndicator.recordError("test error");

        // When checking health
        Health health = healthIndicator.health();

        // Then last error timing is included
        assertThat(health.getDetails()).containsKey("lastErrorMs");
        assertThat(health.getDetails()).containsEntry("lastError", "test error");
    }

    @Test
    void recordError_withNullMessage_usesDefault() {
        // When recording error with null message
        healthIndicator.recordError(null);

        // Then default message is used
        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("lastError", "unknown");
    }

    @Test
    void recordConnectionError_withNullMessage_usesDefault() {
        // When recording connection error with null message
        healthIndicator.recordConnectionError(null);

        // Then default message is used
        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("lastError", "connection_failed");
    }

    @Test
    void markUnhealthy_withNullReason_usesDefault() {
        // When marking unhealthy with null reason
        healthIndicator.markUnhealthy(null);

        // Then default reason is used
        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("lastError", "marked_unhealthy");
    }

    @Test
    void health_successAfterConnectionError_returnsUp() {
        // Given a connection error was recorded
        healthIndicator.recordConnectionError("temporary network issue");
        assertThat(healthIndicator.isHealthy()).isFalse();

        // When success is recorded afterward
        healthIndicator.recordSuccess();

        // Then status returns to UP
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(healthIndicator.isHealthy()).isTrue();
        assertThat(health.getDetails()).containsEntry("connection", "connected");
    }
}
