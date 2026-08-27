package xyz.tcheeric.cashu.mint.admin.rest.nap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import nostr.crypto.bech32.Bech32;

import org.flywaydb.core.Flyway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;

import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.nap.core.ChallengeStore;
import xyz.tcheeric.nap.core.SessionStore;
import xyz.tcheeric.nap.jdbc.JdbcChallengeStore;
import xyz.tcheeric.nap.jdbc.JdbcSessionStore;
import xyz.tcheeric.nap.server.AclResolver;
import xyz.tcheeric.nap.server.acl.PermissionDefinition;
import xyz.tcheeric.nap.server.acl.PermissionRegistry;
import xyz.tcheeric.nap.server.acl.RoleDefinition;
import xyz.tcheeric.nap.spring.config.NapProperties;
import xyz.tcheeric.nap.spring.filter.NapServletFilter;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires NAP into the admin API (issue #372). Each bean here overrides one that
 * {@code NapAutoConfiguration} would otherwise supply as a development default —
 * an allow-all ACL, an empty permission vocabulary, in-memory stores.
 */
@Configuration
@Import(AdminNapConfiguration.Stores.class)
@PropertySource("classpath:nap-defaults.properties")
@ConditionalOnProperty(prefix = "nap", name = "enabled", havingValue = "true")
public class AdminNapConfiguration {

    private static final String NOT_AN_NPUB = "admin.security.super-admin-npub is not a valid npub: ";

    /**
     * The authorisation decision, as one bean. The Super Administrator is decoded
     * from configuration once, here: it is a fact about the deployment, not about
     * the operator store, so the resolver never queries the database to establish it.
     *
     * @param npub bech32 npub from {@code admin.security.super-admin-npub}
     * @param operatorAccessRepository the operator profile store, for everyone else
     * @return the resolver NAP consults on every authenticated request
     */
    @Bean
    public AclResolver adminAclResolver(@Value("${admin.security.super-admin-npub:}") final String npub,
                                        final OperatorAccessRepository operatorAccessRepository) {
        if (npub == null || npub.isBlank()) {
            throw new IllegalStateException("admin.security.super-admin-npub is not set. NAP cannot start without "
                + "a Super Administrator: no other npub can grant the first operator a role.");
        }
        return new AdminAclResolver(decodeNpub(npub.strip()), operatorAccessRepository);
    }

    private static String decodeNpub(final String npub) {
        final String hex;
        try {
            hex = npub.startsWith("npub1") ? Bech32.fromBech32(npub) : null;
        } catch (final Exception ex) {
            throw new IllegalStateException(NOT_AN_NPUB + npub, ex);
        }
        if (hex == null || hex.length() != 64) {
            throw new IllegalStateException(NOT_AN_NPUB + npub);
        }
        return hex.toLowerCase();
    }

    /**
     * The roles and permissions the admin recognises, assembled from the enums in
     * {@code mint-admin-core}. The default role is empty on purpose: authenticating
     * proves who you are, and grants nothing on its own.
     *
     * @return NAP's view of the admin's authorisation vocabulary
     */
    @Bean
    public PermissionRegistry adminPermissionRegistry() {
        final List<PermissionDefinition> permissions = Arrays.stream(AdminPermission.values())
            .map(p -> new PermissionDefinition(p.key(), p.description(), false))
            .toList();
        final List<RoleDefinition> roles = Arrays.stream(AdminRole.values())
            .map(r -> new RoleDefinition(r.key(), r.key(), permissionKeys(r.permissions())))
            .toList();
        return PermissionRegistry.of("cashu-mint-admin", permissions, roles, null);
    }

    private static Set<String> permissionKeys(final Set<AdminPermission> permissions) {
        return permissions.stream().map(AdminPermission::key).collect(Collectors.toSet());
    }

    /**
     * NAP verifies the NIP-98 signature over the exact bytes of the request body, so
     * the body has to be captured before Spring reads it. The auto-configuration
     * leaves this registration to the application by design.
     *
     * @param properties NAP's configuration, for the body cap
     * @return the raw-body filter, bound to the completion endpoint
     */
    @Bean
    public FilterRegistrationBean<NapServletFilter> napServletFilterRegistration(final NapProperties properties) {
        final FilterRegistrationBean<NapServletFilter> registration =
            new FilterRegistrationBean<>(new NapServletFilter("/api/v1/auth/complete", properties.maxBodyBytes()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/v1/auth/*");
        return registration;
    }

    /**
     * NAP's durable stores, nested so they are considered only when NAP itself is
     * enabled. Separate from the beans above because the migrations are
     * PostgreSQL-only: a deployment or test on another database sets
     * {@code nap.jdbc-stores=false} and falls back to the in-memory stores, without
     * losing the authorisation wiring.
     */
    // Deliberately not @Configuration: a static nested @Configuration is a
    // component-scan candidate in its own right, so it would wire NAP's stores even
    // with NAP disabled. Imported by the enclosing class instead, which carries that gate.
    @ConditionalOnProperty(prefix = "nap", name = "jdbc-stores", havingValue = "true", matchIfMissing = true)
    static class Stores {

        private static final String NAP_SCHEMA = "nap";

        /**
         * A second pool on the same database, pinned to the NAP schema, with NAP's own
         * migrations applied first. NAP's JDBC stores name their tables unqualified, so
         * the schema has to come from the connection.
         *
         * <p>The migration runs here rather than from a {@code Flyway} bean: a second
         * such bean makes Spring Boot back off from migrating the admin's own schema.
         *
         * <p>{@code defaultCandidate = false} keeps this datasource out of every by-type
         * injection point in the application: a second {@code DataSource} bean would
         * otherwise make the admin's own repositories ambiguous, or silently pick this one.
         *
         * @param url the admin datasource URL, shared with NAP
         * @param username the database user
         * @param password the database password
         * @return a datasource whose connections default to the NAP schema
         */
        @Bean(destroyMethod = "close", defaultCandidate = false)
        public DataSource napDataSource(@Value("${spring.datasource.url}") final String url,
                                        @Value("${spring.datasource.username:}") final String username,
                                        @Value("${spring.datasource.password:}") final String password) {
            Flyway.configure()
                .dataSource(url, username, password)
                // Flyway scans locations recursively, so the admin's own migrations were
                // moved out to db/migration-admin: left where they were, they would be
                // applied into the nap schema too.
                .locations("classpath:db/migration")
                .schemas(NAP_SCHEMA)
                .defaultSchema(NAP_SCHEMA)
                .table("nap_schema_history")
                .createSchemas(true)
                .load()
                .migrate();

            final HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);
            config.setSchema(NAP_SCHEMA);
            config.setMaximumPoolSize(5);
            config.setPoolName("nap-pool");
            return new HikariDataSource(config);
        }

        @Bean
        public ChallengeStore napChallengeStore(@Qualifier("napDataSource") final DataSource napDataSource) {
            return new JdbcChallengeStore(napDataSource);
        }

        @Bean
        public SessionStore napSessionStore(@Qualifier("napDataSource") final DataSource napDataSource) {
            return new JdbcSessionStore(napDataSource);
        }
    }
}
