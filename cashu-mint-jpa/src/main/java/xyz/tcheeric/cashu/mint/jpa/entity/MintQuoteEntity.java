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
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;

import java.time.Instant;

/**
 * JPA mapping for the {@code mint_quote} table. Mirrors the
 * {@link MintQuote} port. Envers audits lifecycle and financial fields.
 *
 * <p>Spec 001: data-model § MintQuote.
 */
@Entity
@Table(name = "mint_quote")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class MintQuoteEntity implements MintQuote {

    @Id
    @Column(name = "quote_id", length = 64, nullable = false)
    private String quoteId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "unit", length = 16, nullable = false)
    private String unit;

    @Column(name = "mint_url", nullable = false, columnDefinition = "TEXT")
    private String mintUrl;

    @Column(name = "payment_method", length = 32, nullable = false)
    private String paymentMethod;

    @Column(name = "invoice_id", length = 255)
    private String invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", length = 16, nullable = false)
    private LifecycleState lifecycleState;

    /**
     * NUT-20 — the key this quote is locked to, or null when it is unlocked.
     *
     * <p>Immutable once written: re-locking a paid quote to a different key would hand its ecash
     * to whoever made the change.
     */
    @Column(name = "pubkey", length = 66, updatable = false)
    private String pubkey;

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
            lifecycleState = LifecycleState.UNPAID;
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
    public String pubkey() {
        return pubkey;
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
    public String mintUrl() {
        return mintUrl;
    }

    @Override
    public String paymentMethod() {
        return paymentMethod;
    }

    @Override
    public String invoiceId() {
        return invoiceId;
    }

    @Override
    public LifecycleState lifecycleState() {
        return lifecycleState;
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
