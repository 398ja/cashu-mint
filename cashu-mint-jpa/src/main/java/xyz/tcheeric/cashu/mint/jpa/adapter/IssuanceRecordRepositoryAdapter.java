package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.IssuanceRecordEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.IssuanceRecordJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;

import java.util.Optional;

/**
 * JPA-backed implementation of the {@link IssuanceRecordRepository} port. A
 * conflict on the PK is treated as "already issued"; we return the existing
 * row so the caller can serve a NUT-19 replay.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class IssuanceRecordRepositoryAdapter implements IssuanceRecordRepository {

    private final IssuanceRecordJpaRepository jpa;

    @Override
    public Optional<IssuanceRecord> findById(String quoteId) {
        return jpa.findById(quoteId).map(e -> (IssuanceRecord) e);
    }

    @Override
    public InsertResult insertIfAbsent(IssuanceRecord record) {
        Optional<IssuanceRecordEntity> existing = jpa.findById(record.quoteId());
        if (existing.isPresent()) {
            return InsertResult.existing(existing.get());
        }
        try {
            IssuanceRecordEntity entity = (record instanceof IssuanceRecordEntity e)
                    ? e
                    : toEntity(record);
            return InsertResult.newlyInserted(jpa.saveAndFlush(entity));
        } catch (DataIntegrityViolationException conflict) {
            return InsertResult.existing(
                    jpa.findById(record.quoteId())
                            .orElseThrow(() -> conflict));
        }
    }

    private IssuanceRecordEntity toEntity(IssuanceRecord r) {
        IssuanceRecordEntity e = new IssuanceRecordEntity();
        e.setQuoteId(r.quoteId());
        e.setOutputsHash(r.outputsHash());
        e.setSignaturesJson(r.signaturesJson());
        e.setKeysetId(r.keysetId());
        e.setTotalAmount(r.totalAmount());
        return e;
    }
}
