package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import xyz.tcheeric.cashu.mint.jpa.entity.KeysetRedemptionCutoffEntity;

/**
 * Keyset redemption cutoffs, keyed by keyset id (cashu-mint#551).
 *
 * <p>Operator query: which keysets stop redeeming, and when:
 *
 * <pre>{@code
 * SELECT keyset_id, redeem_until, redeem_until <= now() AS retired, reason, set_by, set_at
 *   FROM keyset_redemption_cutoff
 *  ORDER BY redeem_until;
 * }</pre>
 */
public interface KeysetRedemptionCutoffJpaRepository
    extends JpaRepository<KeysetRedemptionCutoffEntity, String> {}
