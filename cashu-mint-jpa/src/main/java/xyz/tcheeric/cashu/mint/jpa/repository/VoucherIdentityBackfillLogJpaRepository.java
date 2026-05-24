package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdentityBackfillLogEntity;

/**
 * Spec 004 T042 / FR-011 — JPA backing for the backfill progress
 * tracker. Read on first-boot to determine whether to run/resume
 * the hash backfill; updated per batch.
 */
@Repository
public interface VoucherIdentityBackfillLogJpaRepository
        extends JpaRepository<VoucherIdentityBackfillLogEntity, String> {
}
