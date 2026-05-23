package xyz.tcheeric.cashu.mint.jpa;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code cashu.mint.jpa.*}. Drives the opt-in DataSource +
 * Flyway wiring in {@link MintJpaAutoConfiguration}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cashu.mint.jpa")
public class MintJpaProperties {

    /** Master switch — when {@code false} the autoconfig stays inert. */
    private boolean enabled = false;

    /** JDBC datasource for the mint quote / issuance / webhook tables. */
    private final DataSource datasource = new DataSource();

    /** Flyway sub-configuration. */
    private final Flyway flyway = new Flyway();

    @Getter
    @Setter
    public static class DataSource {
        private String url = "";
        private String username = "";
        private String password = "";
        private String driverClassName = "org.postgresql.Driver";
    }

    @Getter
    @Setter
    public static class Flyway {
        private boolean enabled = true;
        /**
         * Spec 001 migrations live under a {@code spec001/} subdirectory so they
         * don't collide with V1 migrations shipped by other classpath jars
         * (payment-adapter-model, cashu-vault-jpa).
         */
        private String locations = "classpath:db/migration/spec001";
    }
}
