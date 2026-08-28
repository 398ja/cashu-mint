package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one identity the admin deployment names rather than stores.
 *
 * @param superAdminNpub bech32 npub of the Super Administrator, who is resolved from
 *                       configuration so a database outage or a suspended profile cannot
 *                       lock out the account that recovers the deployment
 */
@ConfigurationProperties(prefix = "admin.security")
public record AdminSecurityProperties(String superAdminNpub) {
}
