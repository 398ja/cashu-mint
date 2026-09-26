package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;

import java.time.Instant;

/**
 * One blind signature the mint has issued, keyed by the blinded message it signed (issue #491).
 *
 * <p>Implements {@link Persistable} so that saving a new row is always an {@code INSERT}. With an
 * assigned identifier Spring Data would otherwise {@code merge}, which reads the existing row and
 * overwrites it, silently turning a second signature on the same {@code B_} into an update
 * instead of the unique violation that refuses it.
 */
@Entity
@Table(name = "blind_signature")
@Getter
@Setter
@NoArgsConstructor
public class BlindSignatureEntity implements Persistable<String> {

    @Id
    @Column(name = "b_", length = 66, nullable = false, updatable = false)
    private String blindedMessage;

    @Column(name = "keyset_id", length = 66, nullable = false, updatable = false)
    private String keysetId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "c_", length = 66, nullable = false, updatable = false)
    private String blindSignature;

    @Column(name = "dleq_e", length = 64, updatable = false)
    private String dleqE;

    @Column(name = "dleq_s", length = 64, updatable = false)
    private String dleqS;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false, updatable = false)
    private SignatureSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newRow = true;

    @Override
    public String getId() {
        return blindedMessage;
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
