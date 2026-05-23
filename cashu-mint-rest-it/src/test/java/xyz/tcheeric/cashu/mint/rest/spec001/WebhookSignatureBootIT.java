package xyz.tcheeric.cashu.mint.rest.spec001;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.cashu.mint.webhook.WebhookConfiguration;
import xyz.tcheeric.cashu.mint.webhook.WebhookSecretStartupValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 T201 / SC-005 — assert that the Spring context refuses to start
 * in any non-{@code local} profile when {@code cashu.mint.webhook.shared-secret}
 * is unset.
 *
 * <p>Uses {@link ApplicationContextRunner} so we can exercise the start-up
 * contract in isolation without booting the full cashu-mint-rest application
 * — which would also bring in Tomcat, JPA, etc. The behaviour under test is
 * purely the {@link WebhookSecretStartupValidator @PostConstruct} guard.
 */
class WebhookSignatureBootIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebhookConfiguration.class))
            .withUserConfiguration(WebhookSecretStartupValidator.class)
            .withPropertyValues(
                    "spring.autoconfigure.exclude="
                            + DataSourceAutoConfiguration.class.getName() + ","
                            + HibernateJpaAutoConfiguration.class.getName());

    @Test
    void stagingProfile_withoutSecret_failsContextStartup() {
        contextRunner
                .withSystemProperties("spring.profiles.active=staging")
                .withPropertyValues("cashu.mint.webhook.shared-secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("staging profile without shared secret must fail to start (FR-007 / SC-005)")
                            .hasStackTraceContaining("cashu.mint.webhook.shared-secret is required");
                });
    }

    @Test
    void prodProfile_withoutSecret_failsContextStartup() {
        contextRunner
                .withSystemProperties("spring.profiles.active=prod")
                .withPropertyValues("cashu.mint.webhook.shared-secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("cashu.mint.webhook.shared-secret is required");
                });
    }

    @Test
    void localProfile_withoutSecret_bootsCleanly() {
        contextRunner
                .withSystemProperties("spring.profiles.active=local")
                .withPropertyValues("cashu.mint.webhook.shared-secret=")
                .run(context -> {
                    // In local profile the @Profile("!local") validator bean
                    // is not registered, so the empty secret is accepted.
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void stagingProfile_withSecret_bootsCleanly() {
        contextRunner
                .withSystemProperties("spring.profiles.active=staging")
                .withPropertyValues("cashu.mint.webhook.shared-secret=staging-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }
}
