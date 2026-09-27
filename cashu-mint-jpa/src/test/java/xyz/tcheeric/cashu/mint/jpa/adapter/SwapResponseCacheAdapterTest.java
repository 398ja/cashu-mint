package xyz.tcheeric.cashu.mint.jpa.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapResponseCacheEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SwapResponseCacheAdapter}, the durable NUT-19 swap cache (issue #482).
 *
 * <p>Postgres behaviour, including a replay served across a restart of the request path, is
 * covered in {@code SwapResponseReplayIT}. These pin the ttl arithmetic and the conflict rule.
 */
class SwapResponseCacheAdapterTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final SwapRequestFingerprint FINGERPRINT = new SwapRequestFingerprint("ab".repeat(32));

    private SwapResponseCacheJpaRepository repository;
    private MintCapabilityProperties capabilities;
    private SwapResponseCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(SwapResponseCacheJpaRepository.class);
        capabilities = new MintCapabilityProperties();
        adapter = new SwapResponseCacheAdapter(repository, Mockito.mock(PlatformTransactionManager.class),
                capabilities, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // A stored entry expires exactly one advertised NUT-19 ttl after it was written, so the
    // lifetime /v1/info promises and the one the mint enforces are the same number.
    @Test
    void anEntryExpiresOneAdvertisedTtlAfterItIsStored() {
        capabilities.setCachedResponseTtl(Duration.ofSeconds(900));

        adapter.store(FINGERPRINT, "{\"signatures\":[]}");

        ArgumentCaptor<SwapResponseCacheEntity> saved = ArgumentCaptor.forClass(SwapResponseCacheEntity.class);
        Mockito.verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRequestFingerprint()).isEqualTo(FINGERPRINT.hex());
        assertThat(saved.getValue().getResponseJson()).isEqualTo("{\"signatures\":[]}");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    // The lookup asks only for entries that have not expired as of now.
    @Test
    void aLookupAsksForAnUnexpiredEntryAsOfNow() {
        SwapResponseCacheEntity stored = new SwapResponseCacheEntity();
        stored.setRequestFingerprint(FINGERPRINT.hex());
        when(repository.findUnexpired(FINGERPRINT.hex(), NOW)).thenReturn(Optional.of(stored));

        assertThat(adapter.find(FINGERPRINT)).containsSame(stored);
    }

    // A conflict with an entry that is already there is the same response stored twice, and
    // is not an error for the swap that lost the race to store it.
    @Test
    void aSecondStoreOfTheSameFingerprintIsIgnored() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(repository.existsById(FINGERPRINT.hex())).thenReturn(true);

        assertThatCode(() -> adapter.store(FINGERPRINT, "{}")).doesNotThrowAnyException();
    }

    // Any other constraint failure is a fault and surfaces, so the caller logs it.
    @Test
    void aConstraintFailureWithoutAnExistingEntryIsRethrown() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check"));
        when(repository.existsById(FINGERPRINT.hex())).thenReturn(false);

        assertThatThrownBy(() -> adapter.store(FINGERPRINT, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
