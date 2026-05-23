package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventId;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEventRepository;

import java.util.Optional;

/**
 * JPA-backed implementation of the {@link WebhookEventRepository} port. PK
 * collisions are translated to {@link DuplicateEventException} so the caller
 * can decide between {@code duplicate} and {@code tamper} outcomes.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class WebhookEventRepositoryAdapter implements WebhookEventRepository {

    private final WebhookEventJpaRepository jpa;

    @Override
    public WebhookEvent insert(WebhookEvent event) throws DuplicateEventException {
        WebhookEventEntity entity = (event instanceof WebhookEventEntity e) ? e : toEntity(event);
        try {
            return jpa.saveAndFlush(entity);
        } catch (DataIntegrityViolationException conflict) {
            WebhookEvent existing = jpa.findById(new WebhookEventId(event.provider(), event.providerEventId()))
                    .map(e -> (WebhookEvent) e)
                    .orElseThrow(() -> conflict);
            throw new DuplicateEventException(existing);
        }
    }

    @Override
    public Optional<WebhookEvent> findById(String provider, String providerEventId) {
        return jpa.findById(new WebhookEventId(provider, providerEventId))
                .map(e -> (WebhookEvent) e);
    }

    private WebhookEventEntity toEntity(WebhookEvent e) {
        WebhookEventEntity entity = new WebhookEventEntity();
        entity.setProvider(e.provider());
        entity.setProviderEventId(e.providerEventId());
        entity.setQuoteId(e.quoteId());
        entity.setAmount(e.amount());
        entity.setUnit(e.unit());
        entity.setPaymentMethod(e.paymentMethod());
        entity.setSignatureDigest(e.signatureDigest());
        entity.setOutcome(e.outcome());
        return entity;
    }
}
