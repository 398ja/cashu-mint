package xyz.tcheeric.cashu.mint.proto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.service.KeysetRedemptionCutoffService.CutoffRefusedException;
import xyz.tcheeric.cashu.mint.proto.service.KeysetRedemptionCutoffService.Refusal;

/** The rules the mint applies before it records a cutoff (cashu-mint#551). */
class KeysetRedemptionCutoffServiceTest {

  private static final String ACTIVE = "01f034be00000000";
  private static final String ARCHIVED = "01ef5b8300000000";
  private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

  private final InMemory cutoffs = new InMemory();
  private KeysetRedemptionCutoffService service;

  /** An in-memory record. */
  static final class InMemory implements xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository {
    private final java.util.Map<String, KeysetRedemptionCutoff> byKeyset = new java.util.HashMap<>();

    @Override
    public java.util.Optional<KeysetRedemptionCutoff> find(final String keysetId) {
      return java.util.Optional.ofNullable(byKeyset.get(keysetId));
    }

    @Override
    public List<KeysetRedemptionCutoff> findAll() {
      return List.copyOf(byKeyset.values());
    }

    @Override
    public void save(final KeysetRedemptionCutoff cutoff) {
      byKeyset.put(cutoff.keysetId(), cutoff);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    final MintLoadService load = Mockito.mock(MintLoadService.class);
    Mockito.when(load.keySets(false)).thenReturn(List.of(KeySet.builder().id(ACTIVE).unit("sat").build()));
    Mockito.when(load.keySets(true)).thenReturn(List.of(KeySet.builder().id(ARCHIVED).unit("sat").build()));
    service = new KeysetRedemptionCutoffService(cutoffs, load, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void recordsAFutureCutoffOnAnArchivedKeyset() throws Exception {
    final KeysetRedemptionCutoff cutoff =
        service.set(ARCHIVED, NOW.plusSeconds(14 * 86_400), "unseeded", "op-1");

    assertThat(cutoffs.find(ARCHIVED)).contains(cutoff);
    assertThat(cutoff.setAt()).isEqualTo(NOW);
  }

  @Test
  void refusesACutoffOnTheActiveKeyset() {
    assertThatThrownBy(() -> service.set(ACTIVE, NOW.plusSeconds(86_400), "no", "op-1"))
        .isInstanceOfSatisfying(CutoffRefusedException.class,
            e -> assertThat(e.refusal()).isEqualTo(Refusal.ACTIVE_KEYSET));
    assertThat(cutoffs.findAll()).isEmpty();
  }

  @Test
  void refusesAnUnknownKeyset() {
    assertThatThrownBy(() -> service.set("00ffffffffffffff", NOW.plusSeconds(86_400), "no", "op-1"))
        .isInstanceOfSatisfying(CutoffRefusedException.class,
            e -> assertThat(e.refusal()).isEqualTo(Refusal.UNKNOWN_KEYSET));
  }

  @Test
  void refusesACutoffThatIsNotInTheFuture() {
    assertThatThrownBy(() -> service.set(ARCHIVED, NOW, "now", "op-1"))
        .isInstanceOfSatisfying(CutoffRefusedException.class,
            e -> assertThat(e.refusal()).isEqualTo(Refusal.NOT_IN_FUTURE));
  }

  @Test
  void refusesACutoffWithLessThanADayOfNoticeEvenWhenCalledDirectly() {
    assertThatThrownBy(() -> service.set(ARCHIVED, NOW.plusSeconds(86_399), "soon", "op-1"))
        .isInstanceOfSatisfying(CutoffRefusedException.class,
            e -> assertThat(e.refusal()).isEqualTo(Refusal.NOT_IN_FUTURE));
  }

  @Test
  void aPassedCutoffIsFinal() {
    cutoffs.save(new KeysetRedemptionCutoff(ARCHIVED, NOW.minusSeconds(1), "done", "op-1", NOW.minusSeconds(99)));

    assertThatThrownBy(() -> service.set(ARCHIVED, NOW.plusSeconds(86_400), "reopen", "op-2"))
        .isInstanceOfSatisfying(CutoffRefusedException.class,
            e -> assertThat(e.refusal()).isEqualTo(Refusal.ALREADY_RETIRED));
  }

  @Test
  void aFutureCutoffMayBeMoved() throws Exception {
    service.set(ARCHIVED, NOW.plusSeconds(86_400), "first", "op-1");
    service.set(ARCHIVED, NOW.plusSeconds(2 * 86_400), "extended", "op-2");

    assertThat(cutoffs.find(ARCHIVED).orElseThrow().redeemUntil()).isEqualTo(NOW.plusSeconds(2 * 86_400));
  }
}
