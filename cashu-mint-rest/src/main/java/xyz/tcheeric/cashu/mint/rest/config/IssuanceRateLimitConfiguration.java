package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Dalia Phase 9 — binds {@link IssuanceRateLimitProperties} into the context. */
@Configuration
@EnableConfigurationProperties(IssuanceRateLimitProperties.class)
public class IssuanceRateLimitConfiguration {
}
