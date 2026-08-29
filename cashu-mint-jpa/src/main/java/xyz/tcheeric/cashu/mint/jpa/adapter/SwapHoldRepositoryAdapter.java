package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapHoldEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapHoldJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JPA-backed swap holds. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SwapHoldRepositoryAdapter implements SwapHoldRepository {

  private static final List<SwapHoldPhase> UNRESOLVED =
      List.of(SwapHoldPhase.HELD, SwapHoldPhase.SIGNING);

  private final SwapHoldJpaRepository holds;

  @Override
  @Transactional
  public void open(String holdId, int inputCount) {
    Instant now = Instant.now();
    SwapHoldEntity entity = new SwapHoldEntity();
    entity.setHoldId(holdId);
    entity.setPhase(SwapHoldPhase.HELD);
    entity.setInputCount(inputCount);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    holds.save(entity);
  }

  /**
   * Advances a hold's phase.
   *
   * <p>A failure here is logged and swallowed rather than thrown. The caller is mid-swap, and the
   * phase is a reconciliation aid rather than the safety mechanism: the hold on the proof rows is
   * what prevents a double spend. Failing the swap because the bookkeeping write failed would
   * turn a recoverable gap in reconciliation into a refused swap.
   */
  @Override
  @Transactional
  public void advance(String holdId, SwapHoldPhase phase) {
    try {
      holds
          .findById(holdId)
          .ifPresent(
              hold -> {
                hold.setPhase(phase);
                hold.setUpdatedAt(Instant.now());
                holds.save(hold);
              });
    } catch (RuntimeException e) {
      log.error(
          "[swap-hold][alert] phase_write_failed hold_id={} phase={} cause={}",
          holdId,
          phase,
          e.getMessage());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SwapHold> findById(String holdId) {
    return holds.findById(holdId).map(SwapHold.class::cast);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SwapHold> findUnresolvedOlderThan(Instant olderThan) {
    return List.copyOf(holds.findUnresolvedOlderThan(UNRESOLVED, olderThan));
  }
}
