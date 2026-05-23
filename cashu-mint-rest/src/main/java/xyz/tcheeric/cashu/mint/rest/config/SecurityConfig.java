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
 * unset/blank, NO admin user is registered: every {@code /admin/**}
 * request returns 401 Unauthorized and a WARN log fires at boot. Set
 * {@code MINT_ADMIN_PASSWORD} (or the equivalent property) to enable
 * operator access.
 *
 * <p>Operators may supply a password with an explicit Spring Security
 * encoder prefix (e.g. {@code {bcrypt}$2a$10$...}); when present, the
 * prefix is preserved verbatim so the {@code DelegatingPasswordEncoder}
 * routes through the named encoder. Plain-text values are wrapped with
 * {@code {noop}} automatically.
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
                        // Spec 003 FR-007: every voucher endpoint requires an
                        // authenticated principal. Today we accept the admin
                        // role; a follow-up adds merchant principals (JWT or
                        // service-account) without changing the contract.
                        .requestMatchers("/v1/vouchers/**").hasRole("ADMIN")
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
        // When the admin password is unset/blank we register ZERO users.
        // The SecurityFilterChain still requires hasRole("ADMIN") on
        // /admin/**, so requests return 401 Unauthorized — there is no
        // guessable fallback credential. Prior versions of this code
        // mounted a sentinel password string ("__admin_password_unset...")
        // which, while non-random, was still source-visible and therefore
        // a publicly-guessable credential. Reverted to fail-closed.
        if (password == null || password.isBlank()) {
            log.warn("Admin auth NOT wired — cashu.mint.admin.password is unset. "
                    + "/admin/** endpoints will reject every request with 401. "
                    + "Set MINT_ADMIN_PASSWORD to enable operator access.");
            return new InMemoryUserDetailsManager();
        }
        // Honour an explicit encoder prefix when the operator supplies one
        // (e.g. {bcrypt}$2a$10$...). Only prefix with {noop} when the
        // value is plain text — otherwise the {bcrypt}/{argon2}/etc.
        // hash would be compared literally and admin auth would silently
        // never accept the correct password.
        String stored = password.startsWith("{") ? password : "{noop}" + password;
        UserDetails admin = User.withUsername(username)
                .password(stored)
                .roles("ADMIN")
                .build();
        log.info("Admin auth wired: username='{}' encoder={}", username,
                password.startsWith("{") ? password.substring(0, password.indexOf('}') + 1) : "{noop}");
        return new InMemoryUserDetailsManager(admin);
    }

    // No explicit PasswordEncoder bean — Spring Security's default
    // DelegatingPasswordEncoder strips the {noop} prefix on the stored
    // password and compares plain text. Operators who want bcrypt can
    // configure cashu.mint.admin.password to start with {bcrypt}<hash>.
}
