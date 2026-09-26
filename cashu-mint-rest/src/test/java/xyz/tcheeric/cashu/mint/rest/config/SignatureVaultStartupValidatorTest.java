package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.SignatureVaultFallbackAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #491: a production profile must refuse to boot with the in-memory signature vault.
 *
 * <p>These boot a real context with the real fallback auto-configuration, so the assertion is
 * about what a deployment actually gets when nothing durable is wired, not about the validator
 * in isolation.
 */
@DisplayName("a production profile refuses to boot with the in-memory signature vault")
class SignatureVaultStartupValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SignatureVaultFallbackAutoConfiguration.class))
            .withUserConfiguration(ValidatorConfiguration.class);

    /** With no durable vault wired, a prod context fails at startup and names the in-memory vault. */
    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "default"})
    void refusesToBootWithTheInMemoryVault(String profile) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DefaultSignatureVaultService")
                    .hasMessageContaining("CASHU_MINT_JPA_ENABLED=true");
        });
    }

    /** With a durable vault wired, a prod context boots and the fallback is never created. */
    @Test
    void bootsWithADurableVault() {
        contextRunner.withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(DurableVaultConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SignatureVaultService.class);
                    assertThat(context.getBean(SignatureVaultService.class).isDurable()).isTrue();
                });
    }

    /** Local and test contexts legitimately run without a database, so the in-memory vault is allowed there. */
    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "websocket-test"})
    void allowsTheInMemoryVaultInTestProfiles(String profile) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** The exemption must match the durable-persistence guard exactly, so neither can drift alone. */
    @Test
    void sharesTheDurablePersistenceExemption() {
        assertThat(SignatureVaultStartupValidator.class.getAnnotation(Profile.class).value())
                .containsExactly(DurablePersistenceStartupValidator.class.getAnnotation(Profile.class).value());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SignatureVaultStartupValidator.class)
    static class ValidatorConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class DurableVaultConfiguration {

        @Bean
        SignatureVaultService durableVault() {
            SignatureVaultService vault = Mockito.mock(SignatureVaultService.class);
            Mockito.when(vault.isDurable()).thenReturn(true);
            return vault;
        }
    }
}
