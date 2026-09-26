package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory vault is a fallback only. Where anything else supplies a
 * {@link SignatureVaultService}, most importantly the durable JPA vault, the fallback must
 * step aside so there is exactly one vault and it is the durable one (issue #491).
 */
class SignatureVaultFallbackAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SignatureVaultFallbackAutoConfiguration.class));

    /** With no other vault present, the in-memory one is provided so local and test contexts still boot. */
    @Test
    void suppliesTheInMemoryVaultWhenNoOtherIsDefined() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SignatureVaultService.class);
            assertThat(context.getBean(SignatureVaultService.class))
                    .isInstanceOf(DefaultSignatureVaultService.class);
        });
    }

    /** A vault defined elsewhere wins and the in-memory fallback is never created. */
    @Test
    void stepsAsideWhenAnotherVaultIsDefined() {
        contextRunner.withUserConfiguration(DurableVaultConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(SignatureVaultService.class);
            assertThat(context).doesNotHaveBean(DefaultSignatureVaultService.class);
            assertThat(context.getBean(SignatureVaultService.class).isDurable()).isTrue();
        });
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
