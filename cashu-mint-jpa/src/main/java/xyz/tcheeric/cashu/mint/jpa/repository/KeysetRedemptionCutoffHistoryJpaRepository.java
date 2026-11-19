package xyz.tcheeric.cashu.mint.jpa.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import xyz.tcheeric.cashu.mint.jpa.entity.KeysetRedemptionCutoffHistoryEntity;

/**
 * The append-only trail of keyset redemption cutoff changes (cashu-mint#551).
 *
 * <p>Operator query: every change to one keyset's cutoff, oldest first:
 *
 * <pre>{@code
 * SELECT redeem_until, reason, set_by, set_at
 *   FROM keyset_redemption_cutoff_history
 *  WHERE keyset_id = :keysetId
 *  ORDER BY set_at, id;
 * }</pre>
 */
public interface KeysetRedemptionCutoffHistoryJpaRepository
    extends JpaRepository<KeysetRedemptionCutoffHistoryEntity, Long> {

  List<KeysetRedemptionCutoffHistoryEntity> findByKeysetIdOrderBySetAtAscIdAsc(String keysetId);
}
