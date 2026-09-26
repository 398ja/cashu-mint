package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.BlindSignatureEntity;

import java.util.List;

/**
 * Spring Data backing for the append-only {@code blind_signature} table (issue #491).
 *
 * <p>Inserts go through {@code saveAndFlush}; a second row for the same {@code b_} surfaces as a
 * {@code DataIntegrityViolationException}, which the adapter translates to
 * {@code outputs_already_signed}.
 */
@Repository
public interface BlindSignatureJpaRepository extends JpaRepository<BlindSignatureEntity, String> {

    /**
     * Operator query behind {@code cashu_mint_issued_amount_total}: the total face value the
     * mint has signed, per keyset.
     *
     * <pre>
     * SELECT keyset_id, SUM(amount) FROM blind_signature GROUP BY keyset_id;
     * </pre>
     *
     * @return one row per keyset that has ever issued a signature
     */
    @Query("SELECT b.keysetId AS keysetId, SUM(b.amount) AS issuedAmount "
            + "FROM BlindSignatureEntity b GROUP BY b.keysetId")
    List<KeysetIssuedAmount> sumIssuedAmountByKeyset();

    /** Total signed amount for one keyset, as returned by {@link #sumIssuedAmountByKeyset()}. */
    interface KeysetIssuedAmount {

        String getKeysetId();

        long getIssuedAmount();
    }
}
