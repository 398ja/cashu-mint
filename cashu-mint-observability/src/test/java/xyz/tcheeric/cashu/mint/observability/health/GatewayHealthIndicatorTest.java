package xyz.tcheeric.cashu.mint.observability.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GatewayHealthIndicator}.
 *
 * Verifies health status determination based on gateway connectivity.
 */
class GatewayHealthIndicatorTest {

    private GatewayHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new GatewayHealthIndicator(5000);
    }

    @Test
    void health_initialState_returnsUp() {
        // When checking health immediately after initialization
        Health health = healthIndicator.health();

        // Then status is UP
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "connected");
    }

    @Test
    void health_afterRecordSuccess_returnsUp() {
        // When recording success
        healthIndicator.recordSuccess();

        // Then status is UP
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("successCount", 1L);
    }

    @Test
    void health_afterMarkUnhealthy_returnsDown() {
        // When marking unhealthy
        healthIndicator.markUnhealthy("Connection refused");

        // Then status is DOWN
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "unhealthy");
        assertThat(health.getDetails()).containsEntry("lastError", "Connection refused");
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
    }

    @Test
    void recordError_incrementsErrorCount() {
        // When recording errors
        healthIndicator.recordError("timeout");
        healthIndicator.recordError("connection_refused");

        // Then error count is incremented
        assertThat(healthIndicator.getErrorCount()).isEqualTo(2);

        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("errorCount", 2L);
        assertThat(health.getDetails()).containsEntry("lastError", "connection_refused");
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
    void reset_clearsAllState() {
        // Given some recorded state
        healthIndicator.recordSuccess();
        healthIndicator.recordError("error");
        healthIndicator.markUnhealthy("reason");

        // When resetting
        healthIndicator.reset();

        // Then all state is cleared
        assertThat(healthIndicator.isHealthy()).isTrue();
        assertThat(healthIndicator.getSuccessCount()).isEqualTo(0);
        assertThat(healthIndicator.getErrorCount()).isEqualTo(0);

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
    void markUnhealthy_withNullReason_usesDefault() {
        // When marking unhealthy with null reason
        healthIndicator.markUnhealthy(null);

        // Then default reason is used
        Health health = healthIndicator.health();
        assertThat(health.getDetails()).containsEntry("lastError", "marked_unhealthy");
    }

    @Test
    void health_successAfterError_returnsUp() {
        // Given an error was recorded
        healthIndicator.recordError("temporary error");

        // When success is recorded afterward
        healthIndicator.recordSuccess();

        // Then status returns to UP
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(healthIndicator.isHealthy()).isTrue();
    }
}
