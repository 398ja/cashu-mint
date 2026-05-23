package xyz.tcheeric.cashu.mint.rest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spec 003 — voucher durability + auth/rate-limit/idempotency knobs.
 *
 * <p>Bound under {@code cashu.mint.voucher} (the existing
 * {@link VoucherProperties} keeps the legacy fee-percentage knob; the two
 * coexist because they govern unrelated concerns).
 *
 * <ul>
 *   <li>{@code iou-policy} — research R6. Defaults to {@code DENY} so production
 *       deploys reject IOU-funded voucher quotes at creation time (FR-006).
 *       Operators flip to {@code ALLOW} only when they have a corresponding
 *       liability process.</li>
 *   <li>{@code idempotency-key-ttl} — FR-009 / research R10. How long a
 *       voucher_idempotency_key row lives. Default 24h matches the voucher
 *       quote TTL.</li>
 *   <li>{@code rate-limit-tokens-per-minute} — FR-008. Per-principal token
 *       bucket capacity. Default 60 (one per second sustained).</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cashu.mint.voucher")
public class VoucherDurabilityProperties {

    /**
     * Whether merchant_iou-funded voucher quotes are permitted in this profile
     * (FR-006). Default {@link IouPolicy#DENY}.
     *
     * <p><strong>FR-006 enforcement status (spec 003):</strong> there is no
     * REST endpoint today that creates IOU-funded voucher quotes through
     * {@code VoucherMintQuoteTask} — the existing voucher creation API only
     * routes the {@code customer_paid} variant. Until an operator-facing IOU
     * creation endpoint is added, this knob is enforced exclusively at
     * <em>issuance</em> time (the {@code VoucherFundingResolverImpl} +
     * {@code MintTask} voucher branch refuse to attach an IOU funding row
     * when the policy is DENY) and via the IOU-issued alert counter
     * ({@code cashu_mint_voucher_iou_issued_total}). When the IOU creation
     * endpoint lands, it MUST also reject at quote-creation time with a typed
     * {@code iou_not_permitted} error per FR-006.
     */
    public enum IouPolicy { ALLOW, DENY }

    private IouPolicy iouPolicy = IouPolicy.DENY;

    private Duration idempotencyKeyTtl = Duration.ofHours(24);

    private int rateLimitTokensPerMinute = 60;
}
