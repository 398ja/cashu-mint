package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIssuanceJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;

import java.util.Optional;

/**
 * Spec 003 — JPA-backed implementation of {@link VoucherIssuanceRepository}.
 * A PK conflict on {@code voucher_quote_id} is treated as "already issued";
 * we return the existing row so a NUT-19 idempotent replay sees the same
 * issuance receipt.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherIssuanceRepositoryAdapter implements VoucherIssuanceRepository {

    private final VoucherIssuanceJpaRepository jpa;

    @Override
    public Optional<VoucherIssuance> findByVoucherQuoteId(String voucherQuoteId) {
        return jpa.findById(voucherQuoteId).map(e -> (VoucherIssuance) e);
    }

    @Override
    public VoucherIssuance insertIfAbsent(VoucherIssuance row) {
        Optional<VoucherIssuanceEntity> existing = jpa.findById(row.voucherQuoteId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            VoucherIssuanceEntity entity = (row instanceof VoucherIssuanceEntity e)
                    ? e
                    : toEntity(row);
            return jpa.saveAndFlush(entity);
        } catch (DataIntegrityViolationException conflict) {
            return jpa.findById(row.voucherQuoteId())
                    .map(e -> (VoucherIssuance) e)
                    .orElseThrow(() -> conflict);
        }
    }

    private VoucherIssuanceEntity toEntity(VoucherIssuance r) {
        VoucherIssuanceEntity e = new VoucherIssuanceEntity();
        e.setVoucherQuoteId(r.voucherQuoteId());
        e.setFundingId(r.fundingId());
        // Spec 004 V20260601_005 dropped the standalone issuance_id column.
        // The VoucherIssuance.issuanceId() value is the same as
        // voucherQuoteId per the R1 namespace invariant; no separate
        // setter call needed.
        e.setOutputsHash(r.outputsHash());
        e.setIssuedAt(r.issuedAt());
        return e;
    }
}
