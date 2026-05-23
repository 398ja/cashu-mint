package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherFundingJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingRepository;

import java.util.Optional;

/**
 * Spec 003 — JPA-backed implementation of {@link VoucherFundingRepository}.
 * Concrete subclass entities ({@code CustomerPaymentFundingEntity},
 * {@code MerchantDebitFundingEntity}, {@code MerchantIouFundingEntity})
 * are created by the resolver / webhook bridge and passed to
 * {@link #save} pre-typed; this adapter only handles dispatch.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherFundingRepositoryAdapter implements VoucherFundingRepository {

    private final VoucherFundingJpaRepository jpa;

    @Override
    public Optional<VoucherFunding> findById(String fundingId) {
        return jpa.findById(fundingId).map(e -> (VoucherFunding) e);
    }

    @Override
    public VoucherFunding save(VoucherFunding funding) {
        if (!(funding instanceof VoucherFundingEntity entity)) {
            throw new IllegalArgumentException(
                    "VoucherFunding.save requires a concrete VoucherFundingEntity subclass; got "
                            + funding.getClass().getName());
        }
        return jpa.save(entity);
    }

    @Override
    public Optional<VoucherFunding> findByProviderEventId(String provider, String providerEventId) {
        return jpa.findByProviderEventId(provider, providerEventId).map(e -> (VoucherFunding) e);
    }
}
