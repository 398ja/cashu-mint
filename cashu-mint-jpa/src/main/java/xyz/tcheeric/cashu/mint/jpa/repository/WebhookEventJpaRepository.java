package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventId;

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
}
