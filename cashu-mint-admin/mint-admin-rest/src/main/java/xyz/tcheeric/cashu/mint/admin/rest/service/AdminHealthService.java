package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.HealthQuery;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.MonitorMintHealthRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.MonitorMintHealthResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.health.HealthSnapshotResponse;

import java.util.Objects;

/**
 * Delegates health monitoring queries to {@link MonitorMintHealthUseCase}.
 */
@Service
public class AdminHealthService {

    private final MonitorMintHealthUseCase healthUseCase;

    public AdminHealthService(final MonitorMintHealthUseCase healthUseCase) {
        this.healthUseCase = Objects.requireNonNull(healthUseCase, "health use case must not be null");
    }

    public HealthSnapshotResponse getHealthSnapshot(final String mintId) {
        Objects.requireNonNull(mintId, "mintId");
        try {
            final MonitorMintHealthResponse response = healthUseCase.handle(
                new MonitorMintHealthRequest(mintId, HealthQuery.SNAPSHOT, "v1"));
            return toResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public HealthSnapshotResponse acknowledgeAlert(final String mintId) {
        Objects.requireNonNull(mintId, "mintId");
        try {
            final MonitorMintHealthResponse response = healthUseCase.handle(
                new MonitorMintHealthRequest(mintId, HealthQuery.ACKNOWLEDGE_ALERT, "v1"));
            return toResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    private static HealthSnapshotResponse toResponse(final MonitorMintHealthResponse response) {
        return new HealthSnapshotResponse(response.mintId(),
            response.status() != null ? response.status().name() : "UNKNOWN",
            response.lifecycleState(), response.checkedAt(), response.message());
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", e.getMessage());
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "health_error", e.getMessage());
    }
}
