package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.ExecuteOperationalControlsResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.OperationalCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlRecord;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

class ExecuteOperationalControlsInteractorTest {

    private static final String MINT_ID = "123e4567-e89b-12d3-a456-426614174010";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174011";

    private InMemoryOperationalControlRepository repository;
    private ExecuteOperationalControlsInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOperationalControlRepository();
        final Clock fixedClock = Clock.fixed(Instant.parse("2026-02-15T01:00:00Z"), ZoneOffset.UTC);
        interactor = new ExecuteOperationalControlsInteractor(repository, fixedClock);
    }

    // Verifies scheduled maintenance transitions to in-progress and completed states.
    @Test
    void shouldScheduleStartAndCompleteMaintenance() {
        final ExecuteOperationalControlsResponse scheduled = interactor.handle(
            new ExecuteOperationalControlsRequest(MINT_ID, OPERATOR_ID,
                OperationalCommand.SCHEDULE_MAINTENANCE, "v1", "planned", 60));

        final ExecuteOperationalControlsResponse started = interactor.handle(
            new ExecuteOperationalControlsRequest(MINT_ID, OPERATOR_ID,
                OperationalCommand.START_MAINTENANCE, "v1", "start", 60));

        final ExecuteOperationalControlsResponse completed = interactor.handle(
            new ExecuteOperationalControlsRequest(MINT_ID, OPERATOR_ID,
                OperationalCommand.COMPLETE_MAINTENANCE, "v1", "done", null));

        assertThat(scheduled.status()).isEqualTo("SCHEDULED");
        assertThat(started.controlId()).isEqualTo(scheduled.controlId());
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(completed.controlId()).isEqualTo(scheduled.controlId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
    }

    // Ensures start maintenance creates an ad-hoc control when no scheduled window exists.
    @Test
    void shouldCreateAdHocMaintenanceWhenMissingScheduledWindow() {
        final ExecuteOperationalControlsResponse started = interactor.handle(
            new ExecuteOperationalControlsRequest(MINT_ID, OPERATOR_ID,
                OperationalCommand.START_MAINTENANCE, "v1", "ad-hoc", 20));

        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(started.message()).isEqualTo("Maintenance started (ad-hoc)");
        assertThat(repository.findActiveMaintenanceByMintId(MintId.fromString(MINT_ID))).isPresent();
    }

    // Confirms completing maintenance without an active window returns a domain not-found error.
    @Test
    void shouldRejectCompleteWhenNoActiveMaintenanceExists() {
        assertThatThrownBy(() -> interactor.handle(
            new ExecuteOperationalControlsRequest(MINT_ID, OPERATOR_ID,
                OperationalCommand.COMPLETE_MAINTENANCE, "v1", "complete", null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("maintenance window not found");
    }

    private static final class InMemoryOperationalControlRepository implements OperationalControlRepository {

        private final Map<String, OperationalControlRecord> records = new ConcurrentHashMap<>();

        @Override
        public void create(final OperationalControlRecord control) {
            records.put(control.controlId(), control);
        }

        @Override
        public void update(final OperationalControlRecord control) {
            records.put(control.controlId(), control);
        }

        @Override
        public Optional<OperationalControlRecord> findActiveMaintenanceByMintId(final MintId mintId) {
            return records.values().stream()
                .filter(record -> record.mintId().equals(mintId))
                .filter(record -> record.controlType() == OperationalControlType.MAINTENANCE)
                .filter(record -> "SCHEDULED".equals(record.status()) || "IN_PROGRESS".equals(record.status()))
                .sorted(Comparator.comparing((OperationalControlRecord record) ->
                        "IN_PROGRESS".equals(record.status()) ? 0 : 1)
                    .thenComparing(OperationalControlRecord::scheduledAt, Comparator.reverseOrder()))
                .findFirst();
        }

        @Override
        public List<OperationalControlRecord> findByMintId(final MintId mintId) {
            return records.values().stream()
                .filter(r -> r.mintId().equals(mintId))
                .toList();
        }
    }
}
