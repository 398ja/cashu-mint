package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spec 003 — registers {@link VoucherDurabilityProperties} unconditionally
 * so the voucher security/rate-limit/idempotency middleware can resolve
 * its knobs regardless of whether the legacy {@code voucher.enabled}
 * flag is on.
 */
@Configuration
@EnableConfigurationProperties(VoucherDurabilityProperties.class)
public class VoucherDurabilityConfiguration {
}
