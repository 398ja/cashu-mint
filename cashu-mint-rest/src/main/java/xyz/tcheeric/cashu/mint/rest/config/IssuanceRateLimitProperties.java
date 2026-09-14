package xyz.tcheeric.cashu.mint.rest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Dalia Phase 9 — per-identity mint-issuance rate limit knobs.
 *
 * <p>Bound under {@code cashu.mint.issuance.rate-limit} (deliberately not under
 * {@code cashu.mint.voucher.*}, to avoid colliding with the unrelated voucher {@code IouPolicy}).
 * The limit covers all {@code /v1/mint} issuance, keyed by the caller's remote address, refined by
 * an engine-supplied identity header when that header can be trusted.
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

    /** Header carrying the engine-supplied caller identity; refines the remote-address bucket. */
    private String identityHeader = "X-Dalia-Identity";

    /**
     * Peers whose {@link #identityHeader} is believed (AppSec finding M-2, issue #425).
     *
     * <p>The header is client-supplied. When it selected the bucket on its own, a caller who
     * could reach {@code /v1/mint} directly rotated it per request and minted a fresh quota every
     * time; the remote-address fallback never engaged, because the attacker always sent one.
     *
     * <p>Entries are literal remote addresses or CIDR blocks, e.g.
     * {@code 10.0.0.0/8,127.0.0.1}. Empty — the default — means no peer is trusted and the header
     * is ignored entirely, which is the safe reading when nobody has stated where the engine sits.
     * A request from a trusted peer still counts against that peer's address; the header only
     * subdivides it. So a spoofed header splits one bucket rather than escaping it.
     */
    private List<String> trustedProxies = new ArrayList<>();
}
