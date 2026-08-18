package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #346 — the guard is the only thing standing between a one-line config change and
 * re-publishing every mint metric on the public API port, so each way of losing the port
 * separation gets its own case.
 */
class ManagementPortGuardTest {

    private ManagementPortGuard guardFor(String managementPort, String serverPort) {
        MockEnvironment env = new MockEnvironment();
        if (managementPort != null) {
            env.setProperty("management.server.port", managementPort);
        }
        env.setProperty("server.port", serverPort);
        return new ManagementPortGuard(env);
    }

    /** The shipped arrangement: distinct ports, boots fine. */
    @Test
    void acceptsADistinctManagementPort() {
        assertThatCode(() -> guardFor("9000", "7777").verifyActuatorIsNotOnThePublicPort())
                .doesNotThrowAnyException();
    }

    /** port=0 means "any free port"; Spring resolves it away from the app port. */
    @Test
    void acceptsRandomManagementPortZero() {
        assertThatCode(() -> guardFor("0", "7777").verifyActuatorIsNotOnThePublicPort())
                .doesNotThrowAnyException();
    }

    /** The leak this issue closes: same port puts actuator back under permitAll(). */
    @Test
    void rejectsAManagementPortEqualToTheServerPort() {
        assertThatThrownBy(() -> guardFor("7777", "7777").verifyActuatorIsNotOnThePublicPort())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ from server.port");
    }

    /** Deleting the property is the same leak, reached a different way. */
    @Test
    void rejectsAMissingManagementPort() {
        assertThatThrownBy(() -> guardFor(null, "7777").verifyActuatorIsNotOnThePublicPort())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port is not set");
    }

    /** -1 disables the management server, which folds actuator back onto the API port. */
    @Test
    void rejectsADisabledManagementServer() {
        assertThatThrownBy(() -> guardFor("-1", "7777").verifyActuatorIsNotOnThePublicPort())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disables the management server");
    }
}
