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
import java.util.UUID;
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
    private RecordingOutboxRepository outboxRepository;
    private ExecuteOperationalControlsInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOperationalControlRepository();
        final Clock fixedClock = Clock.fixed(Instant.parse("2026-02-15T01:00:00Z"), ZoneOffset.UTC);
        outboxRepository = new RecordingOutboxRepository();
        interactor = new ExecuteOperationalControlsInteractor(repository, outboxRepository, fixedClock);
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

    /** Captures what the interactor publishes, so rotation can be asserted. */
    // Rotation must publish durable work rather than reporting success and doing
    // nothing — the placeholder it replaces returned KEY_ROTATION_INITIATED and
    // wrote a single audit row.
    @Test
    void shouldPublishRotationWorkWhenRotatingKeys() {
        final String mintId = UUID.randomUUID().toString();
        final ExecuteOperationalControlsResponse response = interactor.handle(
            new ExecuteOperationalControlsRequest(
                mintId,
                OPERATOR_ID,
                OperationalCommand.ROTATE_KEYS,
                "v1",
                "suspected key compromise",
                null));

        assertThat(response.status()).isEqualTo("KEY_ROTATION_INITIATED");
        assertThat(response.message()).doesNotContain("placeholder");

        assertThat(outboxRepository.appended).hasSize(1);
        final var message = outboxRepository.appended.get(0);
        assertThat(message.eventType())
            .isEqualTo(ExecuteOperationalControlsInteractor.KEYS_ROTATED_EVENT);
        // The control id travels with the message so the adapter can derive the same
        // keyset on redelivery instead of minting a second one.
        assertThat(message.attributes())
            .containsEntry(ExecuteOperationalControlsInteractor.CONTROL_ID_ATTRIBUTE,
                response.controlId());
    }

    private static final class RecordingOutboxRepository
            implements xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository {
        final java.util.List<xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage> appended =
            new java.util.ArrayList<>();

        @Override
        public void append(final xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage message) {
            appended.add(message);
        }

        @Override
        public java.util.List<xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage> findPending(
                final java.time.Instant availableBefore, final int limit) {
            return java.util.List.of();
        }

        @Override
        public void markDispatched(final java.util.UUID eventId, final java.time.Instant dispatchedAt) {
        }

        @Override
        public void recordFailure(final java.util.UUID eventId, final java.time.Instant attemptAt,
                                  final java.time.Instant nextAttemptAt) {
        }
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
        public java.util.Optional<OperationalControlRecord> findById(final String controlId) {
            return java.util.Optional.ofNullable(records.get(controlId));
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
