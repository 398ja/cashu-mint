package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec 004 T041 / FR-003 / FR-010 — append-only audit log of retention
 * purge runs. Lets operators distinguish "anonymous purchase"
 * (identity null + no matching purge_log entry) from "purged"
 * (identity null + matching purge_log entry whose retention_cutoff
 * covers the row's updated_at).
 *
 * <p>Spec 004 § Retention Scope, data-model.md § new tables.
 */
@Entity
@Table(name = "voucher_quote_purge_log")
@Getter
@Setter
@NoArgsConstructor
public class VoucherQuotePurgeLogEntity {

    @Id
    @Column(name = "purge_id", nullable = false, updatable = false)
    private UUID purgeId;

    @Column(name = "purged_at", nullable = false, updatable = false)
    private Instant purgedAt;

    @Column(name = "retention_cutoff", nullable = false, updatable = false)
    private Instant retentionCutoff;

    @Column(name = "rows_purged", nullable = false, updatable = false)
    private long rowsPurged;

    @Column(name = "aud_rows_purged", nullable = false, updatable = false)
    private long audRowsPurged;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @PrePersist
    void onCreate() {
        if (purgeId == null) {
            purgeId = UUID.randomUUID();
        }
        if (purgedAt == null) {
            purgedAt = Instant.now();
        }
    }
}
