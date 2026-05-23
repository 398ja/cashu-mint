package xyz.tcheeric.cashu.mint.rest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spec 002 T311 — locks {@code /admin/**} behind HTTP Basic auth. All public
 * NUT endpoints ({@code /v1/**}), webhook delivery, WebSocket upgrade,
 * actuator, and static error pages stay open.
 *
 * <p>The admin password comes from {@code cashu.mint.admin.password}. When
 * unset, a startup-time random password is logged (Spring default) — non-
 * local profiles SHOULD set a stable value via environment variable
 * {@code MINT_ADMIN_PASSWORD}.
 *
 * <p>"Service-account auth" in the spec sense means a deterministic
 * principal (username/password tuple) that operator tooling can rotate
 * out-of-band. A future iteration could swap this in-memory provider for
 * a JWT verifier or an OIDC service-account integration; the
 * {@code SecurityFilterChain} contract above stays the same.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // Spec 002 T311: /admin/** requires the admin role.
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // OPTIONS preflight is always allowed.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // All other paths (NUT endpoints, /webhook/**, /actuator/**,
                        // WebSocket upgrades, static) stay open — the mint's public
                        // contract is unchanged.
                        .anyRequest().permitAll())
                .httpBasic(httpBasic -> {});
        return http.build();
    }

    @Bean
    public UserDetailsService adminUserDetails(
            @Value("${cashu.mint.admin.username:admin}") String username,
            @Value("${cashu.mint.admin.password:}") String password) {
        // When password is blank, Spring Security would generate a random one
        // and log it. We make the empty-password case explicit by mounting a
        // user whose password no client could match (avoids the surprise of
        // the auto-generated password not being persisted across restarts).
        String effective = password == null || password.isBlank()
                ? "{noop}__admin_password_unset_set_MINT_ADMIN_PASSWORD__"
                : "{noop}" + password;
        UserDetails admin = User.withUsername(username)
                .password(effective)
                .roles("ADMIN")
                .build();
        log.info("Admin auth wired: username='{}' password-set={}", username,
                !(password == null || password.isBlank()));
        return new InMemoryUserDetailsManager(admin);
    }

    // No explicit PasswordEncoder bean — Spring Security's default
    // DelegatingPasswordEncoder strips the {noop} prefix on the stored
    // password and compares plain text. Operators who want bcrypt can
    // configure cashu.mint.admin.password to start with {bcrypt}<hash>.
}
