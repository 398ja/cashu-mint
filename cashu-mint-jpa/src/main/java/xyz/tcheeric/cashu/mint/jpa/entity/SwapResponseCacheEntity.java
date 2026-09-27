package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;

import java.time.Instant;

/**
 * One cached {@code POST /v1/swap} response, keyed by the request's fingerprint (issue #482).
 *
 * <p>Implements {@link Persistable} so saving a new row is always an {@code INSERT}. With an
 * assigned identifier Spring Data would otherwise {@code merge}, silently overwriting a stored
 * response instead of surfacing the conflict the adapter handles.
 */
@Entity
@Table(name = "swap_response_cache")
@Getter
@Setter
@NoArgsConstructor
public class SwapResponseCacheEntity implements CachedSwapResponse, Persistable<String> {

    @Id
    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Transient
    private boolean newRow = true;

    @Override
    public String requestFingerprint() {
        return requestFingerprint;
    }

    @Override
    public String responseJson() {
        return responseJson;
    }

    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String getId() {
        return requestFingerprint;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PrePersist
    void stampCreation() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        newRow = false;
    }
}
