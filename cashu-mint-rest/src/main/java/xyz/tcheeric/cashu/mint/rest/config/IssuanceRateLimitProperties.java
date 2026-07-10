package xyz.tcheeric.cashu.mint.rest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dalia Phase 9 — per-identity mint-issuance rate limit knobs.
 *
 * <p>Bound under {@code cashu.mint.issuance.rate-limit} (deliberately not under
 * {@code cashu.mint.voucher.*}, to avoid colliding with the unrelated voucher {@code IouPolicy}).
 * The limit covers all {@code /v1/mint} issuance, keyed by an engine-supplied identity header when
 * present, else the caller's remote address.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cashu.mint.issuance.rate-limit")
public class IssuanceRateLimitProperties {

    /** Master switch for the per-identity issuance rate limit. */
    private boolean enabled = true;

    /** Max mint requests per identity per minute (burst). Default ~10 (spec §6.4 / O-13). */
    private int perMinuteBurst = 10;

    /** Max mint requests per identity per day. Default ~60 (spec §6.4 / O-13). */
    private int perDay = 60;

    /** Header carrying the engine-supplied caller identity; falls back to the remote address. */
    private String identityHeader = "X-Dalia-Identity";
}
