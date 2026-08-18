package xyz.tcheeric.cashu.mint.rest.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Issue #346 — fails startup if actuator would be served on the public API port.
 *
 * <p>Moving actuator to {@code management.server.port} is what stops anyone who can reach
 * the mint from reading {@code /actuator/prometheus} — issuance rates, outstanding
 * liability, melt-saga failure counts. But that port is env-configurable
 * ({@code CASHU_MINT_MANAGEMENT_PORT}), so a single wrong value puts the whole actuator
 * surface back on the public port underneath {@code anyRequest().permitAll()}. Spring
 * treats that as a perfectly ordinary configuration and boots without comment.
 *
 * <p>So the separation is asserted here instead of merely documented: matching ports, or a
 * management port explicitly disabled with {@code -1}, refuse to start. A leak that
 * announces itself at boot is recoverable; one that waits for someone to notice metrics on
 * the public port is not.
 *
 * <p>{@code management.server.port=0} is allowed — it means "any free port", which
 * Spring resolves to something distinct from the application port. Integration tests use
 * it to stay parallel-safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManagementPortGuard {

    private final Environment environment;

    @PostConstruct
    void verifyActuatorIsNotOnThePublicPort() {
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        Integer serverPort = environment.getProperty("server.port", Integer.class);

        if (managementPort == null) {
            throw new IllegalStateException(
                    "management.server.port is not set, so actuator would be served on the public "
                            + "API port where /actuator/prometheus is readable by anyone who can "
                            + "reach the mint. Set CASHU_MINT_MANAGEMENT_PORT (issue #346).");
        }

        if (managementPort < 0) {
            throw new IllegalStateException(
                    "management.server.port=" + managementPort + " disables the management server, "
                            + "which moves actuator back onto the public API port. Use a distinct "
                            + "port instead (issue #346).");
        }

        // 0 means "pick any free port" — Spring resolves it away from the application port.
        if (managementPort != 0 && managementPort.equals(serverPort)) {
            throw new IllegalStateException(
                    "management.server.port (" + managementPort + ") must differ from server.port ("
                            + serverPort + "): sharing the port exposes /actuator/prometheus on the "
                            + "public API, where it sits behind permitAll() (issue #346).");
        }

        log.info("Actuator is served on management port {}, separate from the public API port {}",
                managementPort == 0 ? "<random>" : managementPort, serverPort);
    }
}
