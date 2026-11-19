package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The redemption cutoff on one keyset (cashu-mint#551). A row exists only for a keyset an
 * operator retired, so the ordinary swap costs a primary-key miss per keyset it names.
 */
@Entity
@Table(name = "keyset_redemption_cutoff")
@Getter
@Setter
@NoArgsConstructor
public class KeysetRedemptionCutoffEntity {

  @Id
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
