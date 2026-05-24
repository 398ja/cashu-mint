package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuotePurgeLogEntity;

import java.util.UUID;

/**
 * Spec 004 T042 / FR-003 / FR-010 — JPA backing for the retention
 * purge audit log. Append-only.
 */
@Repository
public interface VoucherQuotePurgeLogJpaRepository
        extends JpaRepository<VoucherQuotePurgeLogEntity, UUID> {
}
