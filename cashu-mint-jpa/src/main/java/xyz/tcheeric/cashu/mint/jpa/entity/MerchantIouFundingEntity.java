package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.Instant;

/**
 * Spec 003 — voucher funding backed by an explicit merchant IOU. Subject
 * to per-profile policy (FR-006); {@link #policyProfile} captures the
 * profile in effect at issuance so operators can detect drift later
 * (research R6).
 */
@Entity
@Table(name = "merchant_iou_funding")
@DiscriminatorValue("MERCHANT_IOU")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class MerchantIouFundingEntity extends VoucherFundingEntity {

    @jakarta.persistence.Convert(converter = xyz.tcheeric.cashu.mint.jpa.crypto.IdentityHashConverter.class)
    @Column(name = "merchant_id", length = 255, nullable = false)
    private String merchantId;

    @Column(name = "iou_id", length = 255, nullable = false)
    private String iouId;

    /**
     * Spec 003 verbatim terms TEXT column. <strong>Deprecated</strong> —
     * spec 004 V20260601_006 added {@link #iouTermsHash} (SHA-256 hash
     * of the terms document) and backfilled from this column. A
     * follow-up migration drops {@code iou_terms} once backfill is
     * confirmed. New writers MUST populate {@code iou_terms_hash}
     * instead; this field is kept on the entity only for read-through
     * compatibility during the transition window.
     */
    @Deprecated(forRemoval = true)
    @Column(name = "iou_terms", columnDefinition = "TEXT")
    private String iouTerms;

    /**
     * Spec 004 V20260601_006 / research R5b — SHA-256 hash of the
     * IOU terms document. Replaces the verbatim {@link #iouTerms}
     * field; the mint proves "this is the terms document agreed at
     * issuance" without holding sensitive merchant-side content.
     */
    @Column(name = "iou_terms_hash", length = 64)
    private String iouTermsHash;

    @Column(name = "iou_due_at")
    private Instant iouDueAt;

    @Column(name = "policy_profile", length = 16, nullable = false)
    private String policyProfile;

    @Override
    public String merchantId() {
        return merchantId;
    }

    @Override
    public String iouId() {
        return iouId;
    }

    @Override
    public String policyProfile() {
        return policyProfile;
    }
}
