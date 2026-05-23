package xyz.tcheeric.cashu.mint.jpa;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Boot-time activation for the cashu-mint-jpa module (spec 001). Opt-in via
 * {@code cashu.mint.jpa.enabled=true}; when off, none of the beans below are
 * instantiated and the cashu-mint-rest application keeps booting without a
 * database (existing unit-test contexts work unchanged).
 *
 * <p>When enabled the configuration builds:
 * <ul>
 *   <li>A pooled {@link DataSource} from
 *       {@code cashu.mint.jpa.datasource.{url,username,password,driver-class-name}}.</li>
 *   <li>A scoped {@link LocalContainerEntityManagerFactoryBean} bound to the
 *       {@code xyz.tcheeric.cashu.mint.jpa.entity} package. Names are
 *       {@code mintJpaDataSource} and {@code mintEntityManagerFactory} so the
 *       beans coexist with any other persistence units that downstream apps
 *       may register.</li>
 *   <li>A matching {@link PlatformTransactionManager}.</li>
 *   <li>A {@link Flyway} runner pointed at the same datasource — picks up the
 *       {@code db/migration/V20260522_*.sql} migrations shipped by this
 *       module. Toggle with {@code cashu.mint.jpa.flyway.enabled=false} for
 *       contexts that manage schema externally.</li>
 * </ul>
 *
 * <p>Adapters in {@code xyz.tcheeric.cashu.mint.jpa.adapter} are picked up via
 * {@code @Component} scan rooted at the package containing this class.
 */
@Configuration
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MintJpaProperties.class)
@EnableTransactionManagement
@EntityScan(basePackages = "xyz.tcheeric.cashu.mint.jpa.entity")
@EnableJpaRepositories(
        basePackages = "xyz.tcheeric.cashu.mint.jpa.repository",
        entityManagerFactoryRef = "mintEntityManagerFactory",
        transactionManagerRef = "mintTransactionManager")
public class MintJpaAutoConfiguration {

    @Bean(name = "mintJpaDataSource")
    @Primary
    @ConditionalOnMissingBean(name = "mintJpaDataSource")
    public DataSource mintJpaDataSource(MintJpaProperties properties) {
        MintJpaProperties.DataSource ds = properties.getDatasource();
        return DataSourceBuilder.create()
                .driverClassName(ds.getDriverClassName())
                .url(ds.getUrl())
                .username(ds.getUsername())
                .password(ds.getPassword())
                .build();
    }

    @Bean(name = "mintEntityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean mintEntityManagerFactory(
            @org.springframework.beans.factory.annotation.Qualifier("mintJpaDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("xyz.tcheeric.cashu.mint.jpa.entity");
        emf.setPersistenceUnitName("cashu-mint-jpa");
        HibernateJpaVendorAdapter vendor = new HibernateJpaVendorAdapter();
        emf.setJpaVendorAdapter(vendor);
        Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.hbm2ddl.auto", "validate");
        jpaProps.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaProps.put("hibernate.envers.audit_table_suffix", "_aud");
        emf.setJpaPropertyMap(jpaProps);
        return emf;
    }

    @Bean(name = "mintTransactionManager")
    @Primary
    public PlatformTransactionManager mintTransactionManager(
            @org.springframework.beans.factory.annotation.Qualifier("mintEntityManagerFactory")
                    LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }

    @Bean(name = "mintFlyway", initMethod = "migrate")
    @ConditionalOnProperty(prefix = "cashu.mint.jpa.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Flyway mintFlyway(MintJpaProperties properties,
                             @org.springframework.beans.factory.annotation.Qualifier("mintJpaDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.getFlyway().getLocations())
                .placeholders(properties.getFlyway().getPlaceholders())
                .baselineOnMigrate(true)
                .load();
    }
}
