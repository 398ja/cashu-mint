package xyz.tcheeric.cashu.mint.jpa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boot-time activation for the cashu-mint-jpa module (spec 001). Opt-in via
 * {@code cashu.mint.jpa.enabled=true} so existing unit-test contexts that
 * exclude {@code DataSourceAutoConfiguration} continue to boot without a
 * database. Integration tests and production profiles flip the flag on.
 *
 * <p>Scans for entities under {@code xyz.tcheeric.cashu.mint.jpa.entity} and
 * Spring Data repositories under
 * {@code xyz.tcheeric.cashu.mint.jpa.repository}. Port adapters under
 * {@code xyz.tcheeric.cashu.mint.jpa.adapter} are picked up via the
 * {@code @Component} scan rooted at the package containing this class.
 */
@Configuration
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@EntityScan(basePackages = "xyz.tcheeric.cashu.mint.jpa.entity")
@EnableJpaRepositories(basePackages = "xyz.tcheeric.cashu.mint.jpa.repository")
public class MintJpaAutoConfiguration {
}
