package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.util.Optional;

/**
 * Spec 003 — JPA-backed implementation of {@link VoucherQuoteRepository}.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherQuoteRepositoryAdapter implements VoucherQuoteRepository {

    private final VoucherQuoteJpaRepository jpa;

    @Override
    public Optional<VoucherQuote> findById(String quoteId) {
        return jpa.findById(quoteId).map(e -> (VoucherQuote) e);
    }

    @Override
    public VoucherQuote save(VoucherQuote quote) {
        VoucherQuoteEntity entity = (quote instanceof VoucherQuoteEntity existing)
                ? existing
                : toEntity(quote);
        return jpa.save(entity);
    }

    @Override
    public int casLifecycle(String quoteId, VoucherLifecycleState expected, VoucherLifecycleState target) {
        return jpa.casLifecycle(quoteId, expected, target);
    }

    @Override
    public int attachFundingAndAdvance(String quoteId, String fundingId) {
        return jpa.attachFundingAndAdvance(quoteId, fundingId);
    }

    @Override
    public Optional<VoucherQuote> findByIdempotencyKey(String idempotencyKey) {
        return jpa.findByIdempotencyKey(idempotencyKey).map(e -> (VoucherQuote) e);
    }

    private VoucherQuoteEntity toEntity(VoucherQuote q) {
        VoucherQuoteEntity e = new VoucherQuoteEntity();
        e.setQuoteId(q.quoteId());
        e.setVoucherType(q.voucherType());
        e.setFaceValue(q.faceValue());
        e.setChargedAmount(q.chargedAmount());
        e.setFee(q.fee());
        e.setUnit(q.unit());
        e.setMerchantId(q.merchantId());
        e.setCustomerId(q.customerId());
        e.setFundingId(q.fundingId());
        e.setLifecycleState(q.lifecycleState());
        e.setIdempotencyKey(q.idempotencyKey());
        e.setRequestHash(q.requestHash());
        return e;
    }
}
