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

    // Spec 004 V20260601_005 dropped the issuance_id column (research R5c —
    // denormalised mirror with no consumer; YAGNI). The VoucherIssuance
    // port still surfaces issuanceId() for source-compat; this entity
    // returns voucherQuoteId for that method (they were always equal).

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
        // Spec 004 V20260601_005 dropped the standalone issuance_id column.
        // voucher_quote_id and issuance_id were always the same value (per
        // the R1 namespace invariant); return the live PK so existing
        // callers see no behavioural change.
        return voucherQuoteId;
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
