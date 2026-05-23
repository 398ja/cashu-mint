package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.IssuanceRecordEntity;

/**
 * Spring Data JPA backing for the append-only {@code issuance_record} table.
 * Inserts go through {@code save(...)}; reads use {@code findById(quoteId)}.
 * Conflicts on the PK are surfaced as
 * {@code DataIntegrityViolationException} and translated by the adapter to
 * the port's {@code InsertResult.existing(...)} semantics.
 *
 * <p>Spec 001: data-model § IssuanceRecord.
 */
@Repository
public interface IssuanceRecordJpaRepository extends JpaRepository<IssuanceRecordEntity, String> {
}
