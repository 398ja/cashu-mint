package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.time.Instant;

/**
 * Spec 002 data-model § MeltSagaTransition. Append-only timeline; no
 * UPDATEs from application code.
 */
@Entity
@Table(name = "melt_saga_transition")
@IdClass(MeltSagaTransitionId.class)
@Getter
@Setter
@NoArgsConstructor
public class MeltSagaTransitionEntity implements MeltSagaTransition {

    @Id
    @Column(name = "melt_saga_id", length = 64, nullable = false, updatable = false)
    private String meltSagaId;

    @Id
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32, updatable = false)
    private MeltSagaState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", length = 32, nullable = false, updatable = false)
    private MeltSagaState toState;

    @Column(name = "reason", columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "actor", length = 64, nullable = false, updatable = false)
    private String actor;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    @PrePersist
    void onCreate() {
        if (at == null) {
            at = Instant.now();
        }
    }

    @Override public String meltSagaId() { return meltSagaId; }
    @Override public int seq() { return seq; }
    @Override public MeltSagaState fromState() { return fromState; }
    @Override public MeltSagaState toState() { return toState; }
    @Override public String reason() { return reason; }
    @Override public String actor() { return actor; }
    @Override public Instant at() { return at; }
}
