package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;

import java.time.Instant;

/**
 * Durable record of a swap's hold on its input proofs.
 *
 * <p>The proof rows already carry the hold id, so this table exists for the two things they cannot
 * answer after a crash: which holds are unresolved, and whether each had begun signing. The second
 * decides how a stranded hold must be resolved, and the two answers are opposites.
 *
 * <p>Rows are kept after they reach a terminal phase rather than deleted, because the record of a
 * hold that was committed without signatures is what an operator needs to make the affected wallet
 * whole.
 */
@Entity
@Table(name = "swap_hold")
@Getter
@Setter
@NoArgsConstructor
public class SwapHoldEntity implements SwapHold {

  @Id
  @Column(name = "hold_id", nullable = false, length = 64)
  private String holdId;

  @Enumerated(EnumType.STRING)
  @Column(name = "phase", nullable = false, length = 16)
  private SwapHoldPhase phase;

  @Column(name = "input_count", nullable = false)
  private int inputCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Override
  public String holdId() {
    return holdId;
  }

  @Override
  public SwapHoldPhase phase() {
    return phase;
  }

  @Override
  public int inputCount() {
    return inputCount;
  }

  @Override
  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public Instant updatedAt() {
    return updatedAt;
  }
}
