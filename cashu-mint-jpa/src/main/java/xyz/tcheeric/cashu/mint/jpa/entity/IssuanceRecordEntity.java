package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;

import java.time.Instant;

/**
 * JPA mapping for the append-only {@code issuance_record} table. One row per
 * quote (PK on {@code quote_id}). Hibernate is intentionally not Envers-audited
 * here because the row is itself the audit witness — see data-model §
 * IssuanceRecord.
 *
 * <p>Spec 001: data-model § IssuanceRecord.
 */
@Entity
@Table(name = "issuance_record")
@Getter
@Setter
@NoArgsConstructor
public class IssuanceRecordEntity implements IssuanceRecord {

    @Id
    @Column(name = "quote_id", length = 64, nullable = false, updatable = false)
    private String quoteId;

    @Column(name = "outputs_hash", length = 64, nullable = false, updatable = false)
    private String outputsHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signatures_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String signaturesJson;

    @Column(name = "keyset_id", length = 64, nullable = false, updatable = false)
    private String keysetId;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = Instant.now();
        }
    }

    @Override
    public String quoteId() {
        return quoteId;
    }

    @Override
    public String outputsHash() {
        return outputsHash;
    }

    @Override
    public String signaturesJson() {
        return signaturesJson;
    }

    @Override
    public String keysetId() {
        return keysetId;
    }

    @Override
    public long totalAmount() {
        return totalAmount;
    }

    @Override
    public Instant issuedAt() {
        return issuedAt;
    }
}
