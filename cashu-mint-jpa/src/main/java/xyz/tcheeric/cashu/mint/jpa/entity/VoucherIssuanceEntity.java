package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;

import java.time.Instant;

/**
 * Spec 003 — append-only ledger row for voucher issuance. The PK is the
 * voucher_quote_id so a NUT-19 idempotent replay cannot insert a second
 * row for the same quote.
 *
 * <p>Not Envers-audited — append-only by definition.
 */
@Entity
@Table(name = "voucher_issuance")
@Getter
@Setter
@NoArgsConstructor
public class VoucherIssuanceEntity implements VoucherIssuance {

    @Id
    @Column(name = "voucher_quote_id", length = 64, nullable = false, updatable = false)
    private String voucherQuoteId;

    @Column(name = "funding_id", length = 64, nullable = false, updatable = false)
    private String fundingId;

    @Column(name = "issuance_id", length = 64, nullable = false, updatable = false)
    private String issuanceId;

    @Column(name = "outputs_hash", length = 64, nullable = false, updatable = false)
    private String outputsHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = Instant.now();
        }
    }

    @Override
    public String voucherQuoteId() {
        return voucherQuoteId;
    }

    @Override
    public String fundingId() {
        return fundingId;
    }

    @Override
    public String issuanceId() {
        return issuanceId;
    }

    @Override
    public String outputsHash() {
        return outputsHash;
    }

    @Override
    public Instant issuedAt() {
        return issuedAt;
    }
}
