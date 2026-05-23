package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdempotencyKeyEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdempotencyKeyId;

import java.time.Instant;

/**
 * Spec 003 — JPA backing for the {@code voucher_idempotency_key} cache.
 * {@link #deleteExpiredBefore} powers the scheduled TTL sweep.
 */
@Repository
public interface VoucherIdempotencyKeyJpaRepository
        extends JpaRepository<VoucherIdempotencyKeyEntity, VoucherIdempotencyKeyId> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional("mintTransactionManager")
    @Query("DELETE FROM VoucherIdempotencyKeyEntity k WHERE k.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
