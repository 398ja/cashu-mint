package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.HealthQuery;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.HealthStatus;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.MonitorMintHealthRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.MonitorMintHealthResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository.MintHealthSnapshot;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class MonitorMintHealthInteractorTest {

    private static final String MINT_ID = "123e4567-e89b-12d3-a456-426614174099";

    private InMemoryMintHealthRepository repository;
    private MonitorMintHealthInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMintHealthRepository();
        final Clock fixedClock = Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"), ZoneOffset.UTC);
        interactor = new MonitorMintHealthInteractor(repository, fixedClock);
    }

    // Verifies unknown mints return the UNKNOWN status without throwing.
    @Test
    void shouldReturnUnknownForMissingSnapshot() {
        final MonitorMintHealthResponse response = interactor.handle(
            new MonitorMintHealthRequest(MINT_ID, HealthQuery.SNAPSHOT, "v1"));

        assertThat(response.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(response.message()).isEqualTo("No health data available");
    }

    // Confirms persisted health snapshots are returned and can be acknowledged to HEALTHY.
    @Test
    void shouldPersistAndAcknowledgeHealthSnapshot() {
        interactor.updateHealth(MINT_ID, HealthStatus.CRITICAL, "ACTIVE");

        final MonitorMintHealthResponse beforeAck = interactor.handle(
            new MonitorMintHealthRequest(MINT_ID, HealthQuery.SNAPSHOT, "v1"));
        final MonitorMintHealthResponse afterAck = interactor.handle(
            new MonitorMintHealthRequest(MINT_ID, HealthQuery.ACKNOWLEDGE_ALERT, "v1"));

        assertThat(beforeAck.status()).isEqualTo(HealthStatus.CRITICAL);
        assertThat(beforeAck.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(afterAck.status()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(afterAck.message()).isEqualTo("Alert acknowledged, health reset");
    }

    // Ensures acknowledging health on a mint without persisted data fails predictably.
    @Test
    void shouldRejectAcknowledgeForUnknownMint() {
        assertThatThrownBy(() -> interactor.handle(
            new MonitorMintHealthRequest(MINT_ID, HealthQuery.ACKNOWLEDGE_ALERT, "v1")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mint not found");
    }

    private static final class InMemoryMintHealthRepository implements MintHealthRepository {

        private final Map<MintId, MintHealthSnapshot> snapshots = new ConcurrentHashMap<>();

        @Override
        public void upsert(final MintHealthSnapshot snapshot) {
            snapshots.put(snapshot.mintId(), snapshot);
        }

        @Override
        public Optional<MintHealthSnapshot> findByMintId(final MintId mintId) {
            return Optional.ofNullable(snapshots.get(mintId));
        }
    }
}
