package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.ExecuteOperationalControlsResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.OperationalCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository.OperationalControlRecord;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.operations.MaintenanceRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.operations.OperationalControlResponse;

import java.util.List;
import java.util.Objects;

/**
 * Delegates operational control actions to {@link ExecuteOperationalControlsUseCase}.
 */
@Service
public class AdminOperationsService {

    private final ExecuteOperationalControlsUseCase operationalUseCase;
    private final OperationalControlRepository controlRepository;
    private final OperatorIdentity operatorIdentity;

    public AdminOperationsService(final ExecuteOperationalControlsUseCase operationalUseCase,
                                  final OperationalControlRepository controlRepository,
                                  final OperatorIdentity operatorIdentity) {
        this.operationalUseCase = Objects.requireNonNull(operationalUseCase,
            "operational use case must not be null");
        this.controlRepository = Objects.requireNonNull(controlRepository,
            "operational control repository must not be null");
        this.operatorIdentity = Objects.requireNonNull(operatorIdentity, "operator identity must not be null");
    }

    public PagedResponse<OperationalControlResponse> listControls(final String mintId,
                                                                    final int page, final int size) {
        final List<OperationalControlRecord> records = controlRepository.findByMintId(MintId.fromString(mintId));
        final List<OperationalControlResponse> items = records.stream()
                .map(r -> new OperationalControlResponse(r.mintId().asString(), r.controlId(),
                        r.status(), r.scheduledAt(), r.reason()))
                .toList();
        return PagedResponse.of(items, page, size);
    }

    public OperationalControlResponse scheduleMaintenance(final String mintId,
                                                           final MaintenanceRequest request) {
        return executeCommand(mintId, request, OperationalCommand.SCHEDULE_MAINTENANCE);
    }

    public OperationalControlResponse startMaintenance(final String mintId,
                                                        final MaintenanceRequest request) {
        return executeCommand(mintId, request, OperationalCommand.START_MAINTENANCE);
    }

    public OperationalControlResponse completeMaintenance(final String mintId,
                                                           final MaintenanceRequest request) {
        return executeCommand(mintId, request, OperationalCommand.COMPLETE_MAINTENANCE);
    }

    public OperationalControlResponse rotateKeys(final String mintId,
                                                  final MaintenanceRequest request) {
        return executeCommand(mintId, request, OperationalCommand.ROTATE_KEYS);
    }

    public OperationalControlResponse forceClose(final String mintId,
                                                  final MaintenanceRequest request) {
        return executeCommand(mintId, request, OperationalCommand.FORCE_CLOSE);
    }

    private OperationalControlResponse executeCommand(final String mintId,
                                                       final MaintenanceRequest request,
                                                       final OperationalCommand command) {
        Objects.requireNonNull(request, "request");
        try {
            final ExecuteOperationalControlsResponse response = operationalUseCase.handle(
                new ExecuteOperationalControlsRequest(mintId, operatorIdentity.currentOperatorId(),
                    command, "v1", request.reason(), request.durationMinutes()));
            return toResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    private static OperationalControlResponse toResponse(final ExecuteOperationalControlsResponse response) {
        return new OperationalControlResponse(response.mintId(), response.controlId(),
            response.status(), response.scheduledAt(), response.message());
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "maintenance_not_found", e.getMessage());
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "operations_error", e.getMessage());
    }
}
