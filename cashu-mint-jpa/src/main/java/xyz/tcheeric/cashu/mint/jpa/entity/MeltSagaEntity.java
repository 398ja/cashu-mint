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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;

import java.time.Instant;

/**
 * Spec 002 data-model § MeltSaga. Envers audits {@code current_state},
 * {@code payment_hash}, and {@code provider_event_id}.
 */
/**
 * Spec 002 data-model § MeltSaga. Envers audits only
 * {@code current_state}, {@code payment_hash}, and {@code provider_event_id};
 * every other field carries {@link NotAudited} so the {@code melt_saga_aud}
 * table stays narrow and Hibernate's schema validator passes against the
 * Flyway-declared shadow.
 */
@Entity
@Table(name = "melt_saga")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class MeltSagaEntity implements MeltSaga {

    @Id
    @Column(name = "melt_saga_id", length = 64, nullable = false)
    private String meltSagaId;

    @NotAudited
    @Column(name = "quote_id", length = 64, nullable = false, updatable = false)
    private String quoteId;

    @NotAudited
    @Column(name = "invoice_amount", nullable = false, updatable = false)
    private long invoiceAmount;

    @NotAudited
    @Column(name = "exact_fee_reserve", nullable = false, updatable = false)
    private long exactFeeReserve;

    @NotAudited
    @Column(name = "asserted_fee_reserve", updatable = false)
    private Long assertedFeeReserve;

    @NotAudited
    @Column(name = "input_amount", nullable = false, updatable = false)
    private long inputAmount;

    @NotAudited
    @Column(name = "proof_count", nullable = false, updatable = false)
    private int proofCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", length = 32, nullable = false)
    private MeltSagaState currentState;

    @Column(name = "payment_hash", length = 255)
    private String paymentHash;

    @NotAudited
    @Column(name = "provider", length = 64, nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_event_id", length = 255)
    private String providerEventId;

    @NotAudited
    @Column(name = "payment_outcome_reason", columnDefinition = "TEXT")
    private String paymentOutcomeReason;

    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "melt_response_cache", columnDefinition = "jsonb")
    private String meltResponseCache;

    @NotAudited
    @Column(name = "change_outputs_hash", length = 64)
    private String changeOutputsHash;

    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_signatures_json", columnDefinition = "jsonb")
    private String changeSignaturesJson;

    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @NotAudited
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
        if (currentState == null) {
            currentState = MeltSagaState.PROOFS_HELD;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override public String meltSagaId() { return meltSagaId; }
    @Override public String quoteId() { return quoteId; }
    @Override public long invoiceAmount() { return invoiceAmount; }
    @Override public long exactFeeReserve() { return exactFeeReserve; }
    @Override public Long assertedFeeReserve() { return assertedFeeReserve; }
    @Override public long inputAmount() { return inputAmount; }
    @Override public int proofCount() { return proofCount; }
    @Override public MeltSagaState currentState() { return currentState; }
    @Override public String paymentHash() { return paymentHash; }
    @Override public String provider() { return provider; }
    @Override public String providerEventId() { return providerEventId; }
    @Override public String paymentOutcomeReason() { return paymentOutcomeReason; }
    @Override public String meltResponseCache() { return meltResponseCache; }
    @Override public String changeOutputsHash() { return changeOutputsHash; }
    @Override public String changeSignaturesJson() { return changeSignaturesJson; }
    @Override public Instant createdAt() { return createdAt; }
    @Override public Instant updatedAt() { return updatedAt; }
}
