package xyz.tcheeric.cashu.mint.rest.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties controlling simple token authentication for admin endpoints.
 */
@ConfigurationProperties(prefix = "admin.security")
public record AdminSecurityProperties(String apiToken) {

    public AdminSecurityProperties {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("admin.security.api-token must be provided");
        }
    }
}
