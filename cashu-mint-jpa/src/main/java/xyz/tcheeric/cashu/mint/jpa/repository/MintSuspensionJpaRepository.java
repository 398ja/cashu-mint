package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import xyz.tcheeric.cashu.mint.jpa.entity.MintSuspensionEntity;

/**
 * Durable suspension records, keyed by mint id.
 *
 * <p>Operator query — which mints are currently refusing to issue, and why:
 *
 * <pre>{@code
 * SELECT mint_id, reason, suspended_at
 *   FROM mint_suspension
 *  ORDER BY suspended_at DESC;
 * }</pre>
 */
public interface MintSuspensionJpaRepository extends JpaRepository<MintSuspensionEntity, String> {}
