package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapHoldEntity;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;

import java.time.Instant;
import java.util.List;

/**
 * Durable swap holds, keyed by hold id.
 *
 * <p>Operator query — which swaps are holding inputs and how they must be resolved:
 *
 * <pre>{@code
 * SELECT hold_id, phase, input_count, created_at
 *   FROM swap_hold
 *  WHERE phase IN ('HELD', 'SIGNING')
 *  ORDER BY created_at;
 * }</pre>
 *
 * <p>A {@code SIGNING} row must be committed, never released: its outputs may already be in the
 * wild.
 */
public interface SwapHoldJpaRepository extends JpaRepository<SwapHoldEntity, String> {

  /**
   * Unresolved holds that have stopped moving, oldest first.
   *
   * <p>Filtering on {@code updatedAt} rather than {@code createdAt} is what keeps a slow but
   * healthy swap out of the result: a swap that is still working advances its phase, and only one
   * that has stopped becomes eligible.
   */
  @Query("""
      SELECT h FROM SwapHoldEntity h
       WHERE h.phase IN (:unresolved)
         AND h.updatedAt < :olderThan
       ORDER BY h.updatedAt ASC
      """)
  List<SwapHoldEntity> findUnresolvedOlderThan(
      @Param("unresolved") List<SwapHoldPhase> unresolved, @Param("olderThan") Instant olderThan);
}
