package xyz.tcheeric.cashu.mint.proto.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.metrics.KeysetRedemptionMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

/**
 * A retired keyset stops redeeming at its cutoff, and nothing else does (cashu-mint#551).
 */
class RedemptionCutoffGateTest {

  private static final String ACTIVE = "01f034be00000000";
  private static final String RETIRED = "01ef5b8300000000";
  private static final String OTHER_ARCHIVED = "00e3372e00000000";
  private static final Instant CUTOFF = Instant.parse("2026-12-03T00:00:00Z");

  private final InMemoryCutoffs cutoffs = new InMemoryCutoffs();
  private final List<String> refusals = new ArrayList<>();

  @AfterEach
  void resetMetrics() {
    MetricRecorders.registerKeysetRedemption(null);
  }

  private RedemptionCutoffGate gateAt(final Instant now) {
    return new RedemptionCutoffGate(cutoffs, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static KeySetDirectory keySets() throws CashuErrorException {
    final MintLoadService load = Mockito.mock(MintLoadService.class);
    Mockito.when(load.keySets(false)).thenReturn(List.of(keySet(ACTIVE)));
    Mockito.when(load.keySets(true)).thenReturn(List.of(keySet(RETIRED), keySet(OTHER_ARCHIVED)));
    return KeySetDirectory.of(load);
  }

  private static KeySet keySet(final String id) {
    return KeySet.builder().id(id).unit("sat").build();
  }

  private static List<Proof<RandomStringSecret>> inputsOn(final String... keysetIds) {
    final List<Proof<RandomStringSecret>> proofs = new ArrayList<>();
    for (final String id : keysetIds) {
      final RSSProof proof = new RSSProof();
      proof.setAmount(2);
      proof.setKeySetId(id);
      proof.setSecret(RandomStringSecret.create());
      proof.setUnblindedSignature(SignatureTestData.sampleSignature());
      proofs.add(proof);
    }
    return proofs;
  }

  @Test
  void aProofOnARetiredKeysetRedeemsBeforeTheCutoff() {
    cutoffs.save(new KeysetRedemptionCutoff(RETIRED, CUTOFF, "551", "op", null));

    assertThatCode(() -> gateAt(CUTOFF.minusSeconds(1))
            .requireRedeemable(inputsOn(RETIRED), keySets(), RedemptionCutoffGate.SWAP))
        .doesNotThrowAnyException();
  }

  @Test
  void aProofOnARetiredKeysetIsRefusedFromTheCutoffWith12003AndCounted() {
    cutoffs.save(new KeysetRedemptionCutoff(RETIRED, CUTOFF, "551", "op", null));
    MetricRecorders.registerKeysetRedemption(recorder());

    assertThatThrownBy(() -> gateAt(CUTOFF)
            .requireRedeemable(inputsOn(ACTIVE, RETIRED), keySets(), RedemptionCutoffGate.MELT))
        .isInstanceOf(CashuErrorException.class)
        .satisfies(e -> {
          final CashuErrorException error = (CashuErrorException) e;
          assertThat(error.getErrorCode()).isEqualTo(CashuErrorCode.keyset_expired);
          assertThat(error.getErrorCode().getCode()).isEqualTo(12003);
        });
    assertThat(refusals).containsExactly(RETIRED + "/melt");
  }

  @Test
  void keysetsWithoutACutoffAreNeverAffected() {
    cutoffs.save(new KeysetRedemptionCutoff(RETIRED, CUTOFF, "551", "op", null));

    assertThatCode(() -> gateAt(CUTOFF.plusSeconds(86_400))
            .requireRedeemable(inputsOn(ACTIVE, OTHER_ARCHIVED), keySets(), RedemptionCutoffGate.SWAP))
        .doesNotThrowAnyException();
  }

  @Test
  void theActiveKeysetIsNeverRefusedEvenIfARecordNamesIt() {
    // Admin and mint both refuse to set this; the gate still must not take signing down.
    cutoffs.save(new KeysetRedemptionCutoff(ACTIVE, CUTOFF, "fault", "op", null));

    assertThatCode(() -> gateAt(CUTOFF.plusSeconds(60))
            .requireRedeemable(inputsOn(ACTIVE), keySets(), RedemptionCutoffGate.SWAP))
        .doesNotThrowAnyException();
  }

  @Test
  void withNoCutoffRecordInstalledNothingIsRefused() {
    assertThatCode(() -> new RedemptionCutoffGate(null, Clock.systemUTC())
            .requireRedeemable(inputsOn(RETIRED), keySets(), RedemptionCutoffGate.SWAP))
        .doesNotThrowAnyException();
  }

  @Test
  void aKeysetPastItsCutoffIsNoLongerAdvertisedAndOneBeforeItCarriesFinalExpiry() throws Exception {
    final MintLoadService load = Mockito.mock(MintLoadService.class);
    Mockito.when(load.keySets(false)).thenReturn(List.of(keySet(ACTIVE)));
    Mockito.when(load.keySets(true)).thenReturn(List.of(keySet(RETIRED), keySet(OTHER_ARCHIVED)));
    cutoffs.save(new KeysetRedemptionCutoff(RETIRED, CUTOFF, "551", "op", null));

    final List<ActiveKeySet> before = new ActiveKeySetsTask(load, gateAt(CUTOFF.minusSeconds(1))).execute();
    assertThat(before).extracting(ActiveKeySet::getId).containsExactlyInAnyOrder(ACTIVE, RETIRED, OTHER_ARCHIVED);
    final ActiveKeySet scheduled = before.stream().filter(k -> k.getId().equals(RETIRED)).findFirst().orElseThrow();
    assertThat(scheduled.isActive()).isFalse();
    assertThat(scheduled.getFinalExpiry()).isEqualTo(CUTOFF.getEpochSecond());
    final ActiveKeySet untouched = before.stream().filter(k -> k.getId().equals(OTHER_ARCHIVED)).findFirst().orElseThrow();
    assertThat(untouched.isActive()).isFalse();
    assertThat(untouched.getFinalExpiry()).isNull();

    final List<ActiveKeySet> after = new ActiveKeySetsTask(load, gateAt(CUTOFF)).execute();
    assertThat(after).extracting(ActiveKeySet::getId).containsExactlyInAnyOrder(ACTIVE, OTHER_ARCHIVED);
    assertThat(after.stream().filter(k -> k.getId().equals(ACTIVE)).findFirst().orElseThrow().isActive()).isTrue();
  }

  private KeysetRedemptionMetricsRecorder recorder() {
    return (keysetId, operation) -> refusals.add(keysetId + "/" + operation);
  }

  /** An in-memory record, so the tests read like the durable one behaves. */
  static final class InMemoryCutoffs implements KeysetRedemptionCutoffRepository {
    private final Map<String, KeysetRedemptionCutoff> byKeyset = new HashMap<>();

    @Override
    public Optional<KeysetRedemptionCutoff> find(final String keysetId) {
      return Optional.ofNullable(byKeyset.get(keysetId));
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
}
