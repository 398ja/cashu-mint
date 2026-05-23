package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventId;

import java.util.Optional;

/**
 * Spring Data JPA backing for the append-only {@code webhook_event} table.
 * Duplicate inserts (same {@code (provider, provider_event_id)}) surface as
 * {@code DataIntegrityViolationException} and are translated by the adapter to
 * the port's {@code DuplicateEventException}.
 *
 * <p>Spec 001: data-model § WebhookEvent, FR-006.
 */
@Repository
public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, WebhookEventId> {

    /**
     * Spec 003 — voucher funding resolver fallback. Returns the first
     * {@code outcome=accepted} webhook event for the given quote id. Used
     * by {@code VoucherFundingResolverImpl} when the webhook bridge has
     * not yet attached a funding row (e.g. race between webhook delivery
     * and mint request).
     */
    @Query("SELECT e FROM WebhookEventEntity e "
            + "WHERE e.quoteId = :quoteId "
            + "AND e.outcome = xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome.accepted "
            + "ORDER BY e.receivedAt ASC")
    Optional<WebhookEventEntity> findFirstAcceptedByQuoteId(@Param("quoteId") String quoteId);
}
