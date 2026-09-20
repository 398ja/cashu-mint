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
                .withPropertyValues(
                        "cashu.mint.webhook.shared-secret=staging-secret",
                        // b43a45ab made this a second precondition. The runner does
                        // not load application.properties, where it defaults to true,
                        // so it has to be stated here — a real deployment gets it from
                        // MINT_WEBHOOK_REQUIRE_TIMESTAMP or the property file.
                        "cashu.mint.webhook.require-timestamp=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    /**
     * A secret alone is not enough, and this is the case the class was missing.
     *
     * <p>{@code b43a45ab} made {@code require-timestamp} a second precondition
     * for non-local profiles: with it false, an attacker who captured any
     * historical delivery replays it for ever by simply omitting the
     * {@code X-Webhook-Timestamp} header, because the validator then falls back
     * to a bare-body MAC that still verifies. A signature with no replay bound
     * is not much of a defence.
     *
     * <p>The test above was written before that rule existed and had been
     * failing ever since — it set only the secret, so it was asserting that a
     * deployment with replay protection switched off starts cleanly, which is
     * exactly what the new rule forbids. Fixing it by adding the property
     * would have left the rule itself untested, so this asserts it directly.
     */
    @Test
    void stagingProfile_withSecretButNoTimestampRequirement_refusesToStart() {
        contextRunner
                .withSystemProperties("spring.profiles.active=staging")
                .withPropertyValues(
                        "cashu.mint.webhook.shared-secret=staging-secret",
                        "cashu.mint.webhook.require-timestamp=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("require-timestamp must be true");
                });
    }
}
