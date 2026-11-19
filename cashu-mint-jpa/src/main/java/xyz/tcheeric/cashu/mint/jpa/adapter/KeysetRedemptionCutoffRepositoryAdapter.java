package xyz.tcheeric.cashu.mint.jpa.adapter;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.KeysetRedemptionCutoffEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.KeysetRedemptionCutoffHistoryEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.KeysetRedemptionCutoffHistoryJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.KeysetRedemptionCutoffJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository;

/** Durable backing for {@link KeysetRedemptionCutoffRepository} (cashu-mint#551). */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class KeysetRedemptionCutoffRepositoryAdapter implements KeysetRedemptionCutoffRepository {

  private final KeysetRedemptionCutoffJpaRepository cutoffs;
  private final KeysetRedemptionCutoffHistoryJpaRepository history;

  // A single constructor and no injected Clock, for the reason MintSuspensionRepositoryAdapter
  // gives: this application defines no Clock bean.
  private final Clock clock = Clock.systemUTC();

  public KeysetRedemptionCutoffRepositoryAdapter(
      final KeysetRedemptionCutoffJpaRepository cutoffs,
      final KeysetRedemptionCutoffHistoryJpaRepository history) {
    this.cutoffs = cutoffs;
    this.history = history;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KeysetRedemptionCutoff> find(final String keysetId) {
    if (keysetId == null) {
      return Optional.empty();
    }
    return cutoffs.findById(keysetId).map(KeysetRedemptionCutoffRepositoryAdapter::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<KeysetRedemptionCutoff> findAll() {
    return cutoffs.findAll().stream().map(KeysetRedemptionCutoffRepositoryAdapter::toDomain).toList();
  }

  @Override
  @Transactional
  public void save(final KeysetRedemptionCutoff cutoff) {
    final Instant setAt = cutoff.setAt() != null ? cutoff.setAt() : Instant.now(clock);
    final KeysetRedemptionCutoffEntity entity =
        cutoffs.findById(cutoff.keysetId()).orElseGet(KeysetRedemptionCutoffEntity::new);
    entity.setKeysetId(cutoff.keysetId());
    entity.setRedeemUntil(cutoff.redeemUntil());
    entity.setReason(cutoff.reason());
    entity.setSetBy(cutoff.setBy());
    entity.setSetAt(setAt);
    cutoffs.save(entity);

    final KeysetRedemptionCutoffHistoryEntity change = new KeysetRedemptionCutoffHistoryEntity();
    change.setKeysetId(cutoff.keysetId());
    change.setRedeemUntil(cutoff.redeemUntil());
    change.setReason(cutoff.reason());
    change.setSetBy(cutoff.setBy());
    change.setSetAt(setAt);
    history.save(change);
  }

  private static KeysetRedemptionCutoff toDomain(final KeysetRedemptionCutoffEntity entity) {
    return new KeysetRedemptionCutoff(entity.getKeysetId(), entity.getRedeemUntil(),
        entity.getReason(), entity.getSetBy(), entity.getSetAt());
  }
}
