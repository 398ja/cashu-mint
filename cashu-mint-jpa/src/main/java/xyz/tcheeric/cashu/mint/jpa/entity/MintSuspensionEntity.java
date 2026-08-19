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
 * Durable record of a mint being suspended from issuing.
 *
 * <p>A row exists only while the mint is suspended, so the common case costs a primary-key miss. It
 * is durable because a mint that restarts and resumes issuing is the failure suspending is meant to
 * prevent — see ADR-0007.
 */
@Entity
@Table(name = "mint_suspension")
@Getter
@Setter
@NoArgsConstructor
public class MintSuspensionEntity {

  @Id
  @Column(name = "mint_id", nullable = false, length = 64)
  private String mintId;

  @Column(name = "reason")
  private String reason;

  @Column(name = "suspended_at", nullable = false)
  private Instant suspendedAt;
}
