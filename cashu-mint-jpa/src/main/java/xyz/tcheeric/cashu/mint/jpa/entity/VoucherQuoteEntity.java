package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;

import java.time.Instant;

/**
 * Spec 003 — JPA mapping for the {@code voucher_quote} table. Sibling to
 * {@link MintQuoteEntity} (research R1). Envers audits lifecycle and
 * financial fields.
 */
@Entity
@Table(name = "voucher_quote")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class VoucherQuoteEntity implements VoucherQuote {

    @Id
    @Column(name = "quote_id", length = 64, nullable = false)
    private String quoteId;

    @Column(name = "voucher_type", length = 32, nullable = false)
    private String voucherType;

    @Column(name = "face_value", nullable = false)
    private long faceValue;

    @Column(name = "charged_amount", nullable = false)
    private long chargedAmount;

    @Column(name = "fee", nullable = false)
    private long fee;

    @Column(name = "unit", length = 16, nullable = false)
    private String unit;

    @Column(name = "merchant_id", length = 255)
    private String merchantId;

    @Column(name = "customer_id", length = 255)
    private String customerId;

    @Column(name = "funding_id", length = 64)
    private String fundingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", length = 16, nullable = false)
    private VoucherLifecycleState lifecycleState;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (lifecycleState == null) {
            lifecycleState = VoucherLifecycleState.UNFUNDED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public String quoteId() {
        return quoteId;
    }

    @Override
    public String voucherType() {
        return voucherType;
    }

    @Override
    public long faceValue() {
        return faceValue;
    }

    @Override
    public long chargedAmount() {
        return chargedAmount;
    }

    @Override
    public long fee() {
        return fee;
    }

    @Override
    public String unit() {
        return unit;
    }

    @Override
    public String merchantId() {
        return merchantId;
    }

    @Override
    public String customerId() {
        return customerId;
    }

    @Override
    public String fundingId() {
        return fundingId;
    }

    @Override
    public VoucherLifecycleState lifecycleState() {
        return lifecycleState;
    }

    @Override
    public String idempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String requestHash() {
        return requestHash;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public Instant updatedAt() {
        return updatedAt;
    }
}
