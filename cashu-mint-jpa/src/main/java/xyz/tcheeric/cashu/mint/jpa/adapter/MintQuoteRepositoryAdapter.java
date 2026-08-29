package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;

import java.util.Optional;

/**
 * JPA-backed implementation of the {@link MintQuoteRepository} port. Pure
 * translation between the port's domain view and the {@link MintQuoteEntity}
 * Hibernate mapping; no business logic lives here.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class MintQuoteRepositoryAdapter implements MintQuoteRepository {

    private final MintQuoteJpaRepository jpa;

    @Override
    public Optional<MintQuote> findById(String quoteId) {
        return jpa.findById(quoteId).map(e -> (MintQuote) e);
    }

    @Override
    public MintQuote save(MintQuote quote) {
        MintQuoteEntity entity = (quote instanceof MintQuoteEntity existing)
                ? existing
                : toEntity(quote);
        return jpa.save(entity);
    }

    @Override
    public int casLifecycle(String quoteId, LifecycleState expected, LifecycleState target) {
        return jpa.casLifecycle(quoteId, expected, target);
    }

    private MintQuoteEntity toEntity(MintQuote q) {
        MintQuoteEntity e = new MintQuoteEntity();
        e.setQuoteId(q.quoteId());
        e.setAmount(q.amount());
        e.setUnit(q.unit());
        e.setMintUrl(q.mintUrl());
        e.setPaymentMethod(q.paymentMethod());
        e.setInvoiceId(q.invoiceId());
        e.setLifecycleState(q.lifecycleState());
        e.setRequestHash(q.requestHash());
        e.setPubkey(q.pubkey());
        return e;
    }
}
