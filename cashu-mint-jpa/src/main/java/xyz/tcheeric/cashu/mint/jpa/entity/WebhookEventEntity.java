package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import java.time.Instant;

/**
 * JPA mapping for the append-only {@code webhook_event} table.
 * Composite PK {@code (provider, provider_event_id)} expressed via {@link WebhookEventId}.
 *
 * <p>Spec 001: data-model § WebhookEvent.
 */
@Entity
@Table(name = "webhook_event")
@IdClass(WebhookEventId.class)
@Getter
@Setter
@NoArgsConstructor
public class WebhookEventEntity implements WebhookEvent {

    @jakarta.persistence.Id
    @Column(name = "provider", length = 64, nullable = false, updatable = false)
    private String provider;

    @jakarta.persistence.Id
    @Column(name = "provider_event_id", length = 255, nullable = false, updatable = false)
    private String providerEventId;

    @Column(name = "quote_id", length = 64, nullable = false, updatable = false)
    private String quoteId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "unit", length = 16, nullable = false, updatable = false)
    private String unit;

    @Column(name = "payment_method", length = 32, nullable = false, updatable = false)
    private String paymentMethod;

    @Column(name = "signature_digest", length = 64, updatable = false)
    private String signatureDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 32, nullable = false, updatable = false)
    private Outcome outcome;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "raw_body_compressed", updatable = false)
    private byte[] rawBodyCompressed;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String providerEventId() {
        return providerEventId;
    }

    @Override
    public String quoteId() {
        return quoteId;
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
    public String paymentMethod() {
        return paymentMethod;
    }

    @Override
    public String signatureDigest() {
        return signatureDigest;
    }

    @Override
    public Outcome outcome() {
        return outcome;
    }

    @Override
    public Instant receivedAt() {
        return receivedAt;
    }
}
