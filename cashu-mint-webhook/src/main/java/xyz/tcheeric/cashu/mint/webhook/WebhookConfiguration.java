package xyz.tcheeric.cashu.mint.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link WebhookProperties} as a Spring bean. Placed in the webhook
 * module so consumers (the mint REST app) get the binding automatically via
 * Spring Boot's component scan.
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfiguration {
}
