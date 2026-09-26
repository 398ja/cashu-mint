package xyz.tcheeric.cashu.mint.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository.KeysetIssuedAmount;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIssuanceJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The issued-amount series (issue #491): one per keyset, re-derived from the durable
 * signature record on every poll.
 */
class InvariantGaugePollerIssuedAmountTest {

    private BlindSignatureJpaRepository blindSignatures;
    private InvariantMetricsRecorder recorder;
    private final Map<String, Supplier<Number>> boundByKeyset = new HashMap<>();
    private InvariantGaugePoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        blindSignatures = Mockito.mock(BlindSignatureJpaRepository.class);
        recorder = Mockito.mock(InvariantMetricsRecorder.class);
        doAnswer(invocation -> {
            boundByKeyset.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(recorder).bindIssuedAmount(anyString(), any());
        ObjectProvider<InvariantMetricsRecorder> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(recorder);

        poller = new InvariantGaugePoller(
                Mockito.mock(MeltSagaJpaRepository.class),
                Mockito.mock(VoucherIssuanceJpaRepository.class),
                Mockito.mock(VoucherQuoteJpaRepository.class),
                Mockito.mock(MintQuoteJpaRepository.class),
                blindSignatures,
                provider,
                Duration.ofHours(1),
                Duration.ofHours(1));
    }

    /** Each keyset with signatures gets its own series, reading that keyset's summed amount. */
    @Test
    void publishesTheIssuedAmountOfEachKeyset() {
        when(blindSignatures.sumIssuedAmountByKeyset())
                .thenReturn(List.of(issued("00aa", 1500L), issued("00bb", 64L)));

        poller.pollTick();

        assertThat(boundByKeyset.get("00aa").get().longValue()).isEqualTo(1500L);
        assertThat(boundByKeyset.get("00bb").get().longValue()).isEqualTo(64L);
    }

    /** A later poll updates the existing series rather than registering the keyset a second time. */
    @Test
    void aLaterPollUpdatesTheSeriesWithoutBindingItAgain() {
        when(blindSignatures.sumIssuedAmountByKeyset())
                .thenReturn(List.of(issued("00aa", 8L)))
                .thenReturn(List.of(issued("00aa", 24L)));

        poller.pollTick();
        poller.pollTick();

        assertThat(boundByKeyset.get("00aa").get().longValue()).isEqualTo(24L);
        verify(recorder, times(1)).bindIssuedAmount(Mockito.eq("00aa"), any());
    }

    /** A failed query holds the last value and counts a poll failure, so a stale series is alertable. */
    @Test
    void aFailedQueryHoldsTheLastValueAndCountsTheFailure() {
        when(blindSignatures.sumIssuedAmountByKeyset())
                .thenReturn(List.of(issued("00aa", 8L)))
                .thenThrow(new IllegalStateException("database down"));

        poller.pollTick();
        poller.pollTick();

        assertThat(boundByKeyset.get("00aa").get().longValue()).isEqualTo(8L);
        verify(recorder).pollFailed();
    }

    private static KeysetIssuedAmount issued(String keysetId, long amount) {
        return new KeysetIssuedAmount() {
            @Override
            public String getKeysetId() {
                return keysetId;
            }

            @Override
            public long getIssuedAmount() {
                return amount;
            }
        };
    }
}
