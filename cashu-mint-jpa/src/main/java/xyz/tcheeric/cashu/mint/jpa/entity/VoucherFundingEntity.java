package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;

import java.time.Instant;

/**
 * Spec 003 — parent of the JOINED voucher-funding inheritance hierarchy
 * (research R2). The {@code funding_source} discriminator column selects
 * the concrete subclass; per-variant columns live in
 * {@link CustomerPaymentFundingEntity}, {@link MerchantDebitFundingEntity},
 * and {@link MerchantIouFundingEntity}.
 */
@Entity
@Table(name = "voucher_funding")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "funding_source", discriminatorType = DiscriminatorType.STRING, length = 32)
@Audited
@Getter
@Setter
@NoArgsConstructor
public abstract class VoucherFundingEntity implements VoucherFunding {

    @Id
    @Column(name = "funding_id", length = 64, nullable = false)
    private String fundingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "funding_source", length = 32, nullable = false, insertable = false, updatable = false)
    private VoucherFundingSource fundingSource;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "unit", length = 16, nullable = false)
    private String unit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Override
    public String fundingId() {
        return fundingId;
    }

    @Override
    public VoucherFundingSource fundingSource() {
        return fundingSource;
    }

    @Override
    public long amount() {
        return amount;
    }

    @Override
    public String unit() {
        return unit;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }
}
