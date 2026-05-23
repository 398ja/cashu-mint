package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity;

import java.util.Optional;

/**
 * Spec 003 — JPA backing for the polymorphic {@code voucher_funding}
 * hierarchy. Reads typically resolve through the parent class;
 * variant-specific queries hit the child tables directly.
 */
@Repository
public interface VoucherFundingJpaRepository extends JpaRepository<VoucherFundingEntity, String> {

    /**
     * Looks up a {@link CustomerPaymentFundingEntity} by its provider
     * event id. Used by the webhook bridge to enforce idempotency: a
     * webhook replay MUST NOT insert a second funding row for the same
     * {@code (provider, providerEventId)}.
     */
    @Query("SELECT f FROM CustomerPaymentFundingEntity f "
            + "WHERE f.provider = :provider AND f.providerEventId = :providerEventId")
    Optional<CustomerPaymentFundingEntity> findByProviderEventId(
            @Param("provider") String provider,
            @Param("providerEventId") String providerEventId);
}
