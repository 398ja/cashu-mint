package xyz.tcheeric.cashu.mint.admin.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlRecord;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlType;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Implements {@link ExecuteOperationalControlsUseCase} for maintenance windows and operational workflows.
 */
public class ExecuteOperationalControlsInteractor extends AbstractUseCaseInteractor
    implements ExecuteOperationalControlsUseCase {

    private final OperationalControlRepository operationalControlRepository;
    private final Clock clock;

    public ExecuteOperationalControlsInteractor(final OperationalControlRepository operationalControlRepository,
                                                final Clock clock) {
        this.operationalControlRepository = Objects.requireNonNull(operationalControlRepository,
            "operational control repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ExecuteOperationalControlsResponse handle(final ExecuteOperationalControlsRequest request) {
        final ExecuteOperationalControlsRequest validated = requireRequest(request, "execute operational controls request");
        final MintId mintId = validateMintId(validated.mintId());
        final UUID operatorId = validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("operational command must not be null");
        }
        validateVersionTag(validated.versionTag());

        return switch (validated.command()) {
            case SCHEDULE_MAINTENANCE -> scheduleMaintenance(validated, mintId, operatorId);
            case START_MAINTENANCE -> startMaintenance(validated, mintId, operatorId);
            case COMPLETE_MAINTENANCE -> completeMaintenance(validated, mintId, operatorId);
            case ROTATE_KEYS -> rotateKeys(validated, mintId, operatorId);
            case FORCE_CLOSE -> forceClose(validated, mintId, operatorId);
        };
    }

    private ExecuteOperationalControlsResponse scheduleMaintenance(final ExecuteOperationalControlsRequest request,
                                                                  final MintId mintId,
                                                                  final UUID operatorId) {
        final String controlId = UUID.randomUUID().toString();
        final Instant now = clock.instant();
        operationalControlRepository.create(new OperationalControlRecord(
            controlId, mintId, operatorId, OperationalControlType.MAINTENANCE, "SCHEDULED",
            now, request.reason(), request.durationMinutes()));
        return new ExecuteOperationalControlsResponse(request.mintId(), controlId,
            "SCHEDULED", now, request.versionTag(), "Maintenance window scheduled");
    }

    private ExecuteOperationalControlsResponse startMaintenance(final ExecuteOperationalControlsRequest request,
                                                               final MintId mintId,
                                                               final UUID operatorId) {
        final Optional<OperationalControlRecord> activeControl =
            operationalControlRepository.findActiveMaintenanceByMintId(mintId);
        if (activeControl.isEmpty()) {
            final String controlId = UUID.randomUUID().toString();
            final Instant now = clock.instant();
            operationalControlRepository.create(new OperationalControlRecord(
                controlId, mintId, operatorId, OperationalControlType.MAINTENANCE, "IN_PROGRESS",
                now, request.reason(), request.durationMinutes()));
            return new ExecuteOperationalControlsResponse(request.mintId(), controlId,
                "IN_PROGRESS", now, request.versionTag(), "Maintenance started (ad-hoc)");
        }
        final OperationalControlRecord control = activeControl.orElseThrow();
        final OperationalControlRecord updated = new OperationalControlRecord(
            control.controlId(),
            control.mintId(),
            operatorId,
            control.controlType(),
            "IN_PROGRESS",
            control.scheduledAt(),
            request.reason() != null ? request.reason() : control.reason(),
            request.durationMinutes() != null ? request.durationMinutes() : control.durationMinutes());
        operationalControlRepository.update(updated);
        return new ExecuteOperationalControlsResponse(request.mintId(), control.controlId(),
            "IN_PROGRESS", clock.instant(), request.versionTag(), "Maintenance started");
    }

    private ExecuteOperationalControlsResponse completeMaintenance(final ExecuteOperationalControlsRequest request,
                                                                  final MintId mintId,
                                                                  final UUID operatorId) {
        final Optional<OperationalControlRecord> activeControl =
            operationalControlRepository.findActiveMaintenanceByMintId(mintId);
        if (activeControl.isEmpty()) {
            throw new IllegalStateException("active maintenance window not found for mint: " + request.mintId());
        }
        final OperationalControlRecord control = activeControl.orElseThrow();
        final OperationalControlRecord updated = new OperationalControlRecord(
            control.controlId(),
            control.mintId(),
            operatorId,
            control.controlType(),
            "COMPLETED",
            control.scheduledAt(),
            request.reason() != null ? request.reason() : control.reason(),
            control.durationMinutes());
        operationalControlRepository.update(updated);
        return new ExecuteOperationalControlsResponse(request.mintId(), control.controlId(),
            "COMPLETED", clock.instant(), request.versionTag(), "Maintenance completed");
    }

    private ExecuteOperationalControlsResponse rotateKeys(final ExecuteOperationalControlsRequest request,
                                                          final MintId mintId,
                                                          final UUID operatorId) {
        final String controlId = UUID.randomUUID().toString();
        final Instant now = clock.instant();
        operationalControlRepository.create(new OperationalControlRecord(
            controlId, mintId, operatorId, OperationalControlType.KEY_ROTATION,
            "KEY_ROTATION_INITIATED", now, request.reason(), request.durationMinutes()));
        return new ExecuteOperationalControlsResponse(request.mintId(), controlId,
            "KEY_ROTATION_INITIATED", now, request.versionTag(),
            "Key rotation initiated (placeholder)");
    }

    private ExecuteOperationalControlsResponse forceClose(final ExecuteOperationalControlsRequest request,
                                                          final MintId mintId,
                                                          final UUID operatorId) {
        final String controlId = UUID.randomUUID().toString();
        final Instant now = clock.instant();
        operationalControlRepository.create(new OperationalControlRecord(
            controlId, mintId, operatorId, OperationalControlType.FORCE_CLOSE,
            "FORCE_CLOSED", now, request.reason(), request.durationMinutes()));
        return new ExecuteOperationalControlsResponse(request.mintId(), controlId,
            "FORCE_CLOSED", now, request.versionTag(),
            "Mint force-closed by operator " + request.operatorId());
    }
}
