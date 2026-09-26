package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

/**
 * Supplies the in-memory {@link DefaultSignatureVaultService} when nothing else provides a
 * {@link SignatureVaultService}.
 *
 * <p>This is an auto-configuration rather than a {@code @Service} on purpose.
 * {@code @ConditionalOnMissingBean} is only reliable where Spring evaluates it after every
 * candidate is registered, and component-scanned beans are registered in classpath order, so a
 * scanned fallback could be created alongside, or instead of, the durable vault depending on
 * which class the scanner met first. Auto-configurations are processed after all user
 * configuration and component scanning, and this one additionally runs after
 * {@code MintJpaAutoConfiguration}, so the durable vault is always visible when the condition
 * is checked.
 *
 * <p>The fallback exists for local and test contexts. Production profiles refuse to boot with
 * it, because a volatile vault forgets every signed output on restart (issue #491).
 */
@AutoConfiguration(afterName = "xyz.tcheeric.cashu.mint.jpa.MintJpaAutoConfiguration")
public class SignatureVaultFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SignatureVaultService.class)
    public SignatureVaultService inMemorySignatureVaultService() {
        return new DefaultSignatureVaultService();
    }
}
