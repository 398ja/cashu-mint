package xyz.tcheeric.cashu.mint.rest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks the operational endpoints on the management port.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Actuator moved to its own port in issue #346, and the isolation argument was that the port
 * is simply not published. That is true of the reference compose file and nothing else. The image
 * {@code EXPOSE}s 9000, the bind address is {@code 0.0.0.0} by necessity (Kubernetes probes hit
 * the pod IP, and Prometheus scrapes from a sibling container, so loopback would break both), and
 * the public chain deliberately does not map the management port at all. Anything that could
 * reach the port could therefore read {@code /actuator/prometheus} unauthenticated: issuance and
 * melt rates, outstanding liability gauges, lock contention, saga failure counts.
 *
 * <p>Not-publishing-the-port is a deployment convention. This chain is an enforced control, so
 * the two together are defence in depth rather than a single convention doing all the work.
 *
 * <h2>What stays open</h2>
 *
 * <p>Liveness and readiness probes stay anonymous: the kubelet cannot present credentials, and a
 * probe that 401s is a pod that never goes ready. They expose only UP/DOWN. Everything else on
 * the management port, {@code /actuator/prometheus} and {@code /actuator/metrics/**} included,
 * requires the operator credential that already guards {@code /admin/**}.
 *
 * <p>When {@code cashu.mint.admin.password} is unset no user is registered at all, so those
 * endpoints return 401 rather than falling open. A Prometheus scrape config therefore needs
 * {@code basic_auth} pointing at the same credential.
 */
@Slf4j
@Configuration
public class ManagementSecurityConfig {

    /**
     * Ordered ahead of {@link SecurityConfig}'s chain so the management endpoints are matched
     * here first; the public chain's {@code anyRequest().permitAll()} would otherwise claim them.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http,
            @Value("${cashu.mint.actuator.require-auth:true}") boolean requireAuth) throws Exception {

        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!requireAuth) {
            // An explicit opt-out for operators whose metrics port is genuinely unreachable and
            // whose scrape tooling cannot present credentials. Loud, because the default is the
            // safe one and this undoes it.
            log.warn("Actuator auth DISABLED by cashu.mint.actuator.require-auth=false — "
                    + "/actuator/prometheus and /actuator/metrics are readable by anyone who can "
                    + "reach the management port.");
            http.authorizeHttpRequests(authz -> authz.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(authz -> authz
                        // Probes must stay anonymous: the kubelet cannot authenticate, and these
                        // report only UP/DOWN.
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        // Everything else on this port is operational data.
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults());

        log.info("Actuator auth wired: health is anonymous, all other actuator endpoints require "
                + "the ADMIN role.");
        return http.build();
    }
}
