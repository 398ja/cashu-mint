package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One change to a keyset's redemption cutoff (cashu-mint#551). Append-only: the trail of who set
 * or moved a cutoff, and when, survives every later change.
 */
@Entity
@Table(name = "keyset_redemption_cutoff_history")
@Getter
@Setter
@NoArgsConstructor
public class KeysetRedemptionCutoffHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "keyset_id", nullable = false, length = 66)
  private String keysetId;

  @Column(name = "redeem_until", nullable = false)
  private Instant redeemUntil;

  @Column(name = "reason")
  private String reason;

  @Column(name = "set_by")
  private String setBy;

  @Column(name = "set_at", nullable = false)
  private Instant setAt;
}
