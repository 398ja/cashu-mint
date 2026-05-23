package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKey;

import java.time.Instant;

/**
 * Spec 003 — durable Idempotency-Key cache for voucher POST endpoints.
 * Same {@code (idempotency_key, principal_id)} + matching
 * {@code request_hash} returns the cached response; same key with a
 * different hash is rejected as a tamper signal (FR-009).
 */
@Entity
@Table(name = "voucher_idempotency_key")
@Getter
@Setter
@NoArgsConstructor
public class VoucherIdempotencyKeyEntity implements VoucherIdempotencyKey {

    @EmbeddedId
    private VoucherIdempotencyKeyId id;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    @Column(name = "response_body_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String responseBodyJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Override
    public String idempotencyKey() {
        return id != null ? id.getIdempotencyKey() : null;
    }

    @Override
    public String principalId() {
        return id != null ? id.getPrincipalId() : null;
    }

    @Override
    public String requestHash() {
        return requestHash;
    }

    @Override
    public int responseStatus() {
        return responseStatus;
    }

    @Override
    public String responseBodyJson() {
        return responseBodyJson;
    }

    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }
}
