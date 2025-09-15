package xyz.tcheeric.cashu.mint.admin.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import xyz.tcheeric.cashu.mint.admin.config.properties.MintAdminProperties;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MintAdminProperties.class)
@RequiredArgsConstructor
public class AdminPersistenceConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminPersistenceConfiguration.class);

    private final DataSourceProperties dataSourceProperties;
    private final MintAdminProperties mintAdminProperties;

    @PostConstruct
    void validateDataSourceConfiguration() {
        if (!StringUtils.hasText(dataSourceProperties.getUrl())) {
            throw new IllegalStateException("spring.datasource.url must be configured for the active profile");
        }
    }

    @Bean
    @Primary
    @Profile("postgres")
    public DataSource postgresDataSource() {
        return buildHikariDataSource("mint-admin-postgres");
    }

    @Bean
    @Primary
    @Profile("h2")
    public DataSource h2DataSource() {
        HikariDataSource dataSource = buildHikariDataSource("mint-admin-h2");
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    private HikariDataSource buildHikariDataSource(String poolName) {
        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName(poolName);
        dataSource.setInitializationFailTimeout(0);
        return dataSource;
    }

    @Bean
    @ConditionalOnClass(Flyway.class)
    @ConditionalOnProperty(prefix = "mint.admin.migrations.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FlywayConfigurationCustomizer mintAdminFlywayConfigurationCustomizer() {
        return configuration -> {
            var flyway = mintAdminProperties.getMigrations().getFlyway();
            if (!flyway.getLocations().isEmpty()) {
                configuration.locations(flyway.getLocations().toArray(String[]::new));
            }
        };
    }

    @Bean
    @ConditionalOnClass(Flyway.class)
    @ConditionalOnProperty(prefix = "mint.admin.migrations.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FlywayMigrationStrategy mintAdminFlywayMigrationStrategy() {
        return flyway -> {
            var flywayProperties = mintAdminProperties.getMigrations().getFlyway();
            if (flywayProperties.isCleanBeforeMigrate()) {
                if (flyway.getConfiguration().isCleanDisabled()) {
                    LOGGER.warn("Flyway clean requested but flyway.cleanDisabled=true; skipping clean phase");
                } else {
                    flyway.clean();
                }
            }
            if (flywayProperties.isRepairOnMigrate()) {
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    @Bean
    @ConditionalOnClass(SpringLiquibase.class)
    @ConditionalOnProperty(prefix = "mint.admin.migrations.liquibase", name = "enabled", havingValue = "true")
    public SpringLiquibase mintAdminLiquibase(DataSource dataSource) {
        var liquibaseProperties = mintAdminProperties.getMigrations().getLiquibase();
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(liquibaseProperties.getChangeLog());
        liquibase.setShouldRun(true);
        liquibase.setDropFirst(liquibaseProperties.isDropFirst());

        if (StringUtils.hasText(liquibaseProperties.getDefaultSchema())) {
            liquibase.setDefaultSchema(liquibaseProperties.getDefaultSchema());
        }

        if (!liquibaseProperties.getContexts().isEmpty()) {
            liquibase.setContexts(String.join(",", liquibaseProperties.getContexts()));
        }

        return liquibase;
    }
}
