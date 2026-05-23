package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Spec 004 T040 / FR-011 — boot-time identity backfill progress tracker.
 * One row per voucher table (live + Envers _aud variants). When
 * {@code completed_at IS NOT NULL} the table has zero remaining raw
 * npubs and the voucher endpoints can serve traffic.
 *
 * <p>Spec 004 § Retention Scope and data-model.md § new tables.
 */
@Entity
@Table(name = "voucher_identity_backfill_log")
@Getter
@Setter
@NoArgsConstructor
public class VoucherIdentityBackfillLogEntity {

    @Id
    @Column(name = "table_name", length = 64, nullable = false, updatable = false)
    private String tableName;

    @Column(name = "last_hashed_pk", length = 64)
    private String lastHashedPk;

    @Column(name = "rows_hashed", nullable = false)
    private long rowsHashed;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "version", nullable = false)
    private long version;
}
