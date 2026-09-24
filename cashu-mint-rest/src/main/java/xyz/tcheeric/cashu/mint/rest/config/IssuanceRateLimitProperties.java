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

    /**
     * Max mint requests per identity per day.
     *
     * <p>WAS 60, WHICH WAS SIX MINUTES OF THE BURST. That pair could not describe one intended
     * load: it said the mint accepts 10 a minute, but only for six minutes, then nothing until
     * tomorrow. And because no caller in this deployment sends an identity header, the bucket
     * keys on the gateway's address alone, so the sixty were shared by every merchant behind it.
     * Ten stalls selling twenty coupons each need 200 and were refused before lunch; staging
     * burned 48 of 60 on test traffic alone.
     *
     * <p>2000 is roughly three hours of sustained burst, which is the honest daily reading of
     * "10 a minute is acceptable". It is a CEILING ON DAMAGE, not a forecast: the per-minute
     * burst is the limit doing the real work, bounding any single caller to 10/min whatever else
     * breaks. This one exists so a runaway that stays under the burst still stops eventually.
     *
     * <p>Lower it once identities are per-issuer rather than per-address, because 2000 shared by
     * a deployment and 2000 per merchant are very different numbers.
     */
    private int perDay = 2000;

    /**
     * Header carrying the caller identity; refines the remote-address bucket.
     *
     * <p>RENAMED FROM {@code X-Dalia-Identity}, with no alias, because nothing sends the old name.
     * This filter was built for Dalia Phase 9 and the header name came with it, but the mint is
     * not a Dalia component: Dalia's SDK sends that header to a Dalia ENGINE, Dalia is not
     * deployed in this stack, and no Imani gateway sends it either. Checked before removing.
     *
     * <p>A name that misidentifies which system owns a wire contract misleads every later reader,
     * and this one already did. Keeping a compatibility alias for a caller that does not exist
     * would preserve exactly that confusion, so the old name is simply gone. If Dalia is ever
     * pointed at this mint it sets this property; that is what the property is for.
     */
    private String identityHeader = "X-Mint-Issuer-Identity";

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
     * Listing a peer means asserting its identity header is trustworthy: each distinct header value
     * from that peer receives its own full-sized bucket. That is what makes a real proxy useful,
     * and it is also why the list is empty by default -- an entry reachable by untrusted callers
     * lets them multiply their quota by rotating the header.
     */
    private List<String> trustedProxies = new ArrayList<>();
}
