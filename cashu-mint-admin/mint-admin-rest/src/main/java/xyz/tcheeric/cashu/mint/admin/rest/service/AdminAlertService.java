package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.ManageNotificationsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.ManageNotificationsResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.NotificationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository.AlertRecord;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.AlertActionRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.AlertActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.CreateAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.EscalateAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.SilenceAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;

import java.util.List;
import java.util.Objects;

/**
 * Delegates alert workflows to {@link ManageNotificationsUseCase}.
 */
@Service
public class AdminAlertService {

    private final ManageNotificationsUseCase notificationsUseCase;
    private final AlertRepository alertRepository;

    public AdminAlertService(final ManageNotificationsUseCase notificationsUseCase,
                             final AlertRepository alertRepository) {
        this.notificationsUseCase = Objects.requireNonNull(notificationsUseCase,
            "notifications use case must not be null");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alert repository must not be null");
    }

    public PagedResponse<AlertActionResponse> listAlerts(final String severity, final Boolean acknowledged,
                                                          final Boolean silenced, final String mintId,
                                                          final int page, final int size) {
        List<AlertRecord> alerts = alertRepository.findAll();
        if (severity != null && !severity.isBlank()) {
            alerts = alerts.stream().filter(a -> a.severity().equalsIgnoreCase(severity)).toList();
        }
        if (acknowledged != null) {
            alerts = alerts.stream().filter(a -> a.acknowledged() == acknowledged).toList();
        }
        if (silenced != null) {
            alerts = alerts.stream().filter(a -> a.silenced() == silenced).toList();
        }
        if (mintId != null && !mintId.isBlank()) {
            alerts = alerts.stream().filter(a -> a.mintId().equals(mintId)).toList();
        }
        final List<AlertActionResponse> items = alerts.stream()
                .map(a -> new AlertActionResponse(a.alertId(), a.mintId(), a.severity(), a.summary(),
                        a.acknowledged(), a.silenced(), a.silenceMinutes(), a.escalations(), null))
                .toList();
        return PagedResponse.of(items, page, size);
    }

    public AlertActionResponse getAlert(final String alertId) {
        final AlertRecord alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new AdminServiceException(
                        HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found: " + alertId));
        return new AlertActionResponse(alert.alertId(), alert.mintId(), alert.severity(), alert.summary(),
                alert.acknowledged(), alert.silenced(), alert.silenceMinutes(), alert.escalations(), null);
    }

    public AlertActionResponse createAlert(final CreateAlertRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageNotificationsResponse response = notificationsUseCase.handle(
                new ManageNotificationsRequest(request.mintId(), null,
                    NotificationCommand.CREATE_ALERT, "v1",
                    request.alertId(), request.severity(), request.summary(),
                    request.labels(), null, null));
            return toAlertResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public AlertActionResponse acknowledgeAlert(final String alertId, final AlertActionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageNotificationsResponse response = notificationsUseCase.handle(
                new ManageNotificationsRequest(null, null,
                    NotificationCommand.ACKNOWLEDGE, "v1",
                    alertId, null, null, null, null, request.reason()));
            return toAlertResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public AlertActionResponse silenceAlert(final String alertId, final SilenceAlertRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageNotificationsResponse response = notificationsUseCase.handle(
                new ManageNotificationsRequest(null, null,
                    NotificationCommand.SILENCE_ALERT, "v1",
                    alertId, null, null, null, request.durationMinutes(), request.reason()));
            return toAlertResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public AlertActionResponse unsilenceAlert(final String alertId, final AlertActionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageNotificationsResponse response = notificationsUseCase.handle(
                new ManageNotificationsRequest(null, null,
                    NotificationCommand.UNSILENCE_ALERT, "v1",
                    alertId, null, null, null, null, request.reason()));
            return toAlertResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public AlertActionResponse escalateAlert(final String alertId, final EscalateAlertRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageNotificationsResponse response = notificationsUseCase.handle(
                new ManageNotificationsRequest(null, request.policyId(),
                    NotificationCommand.ESCALATE_ALERT, "v1",
                    alertId, null, null, null, null, request.reason()));
            return toAlertResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    private static AlertActionResponse toAlertResponse(final ManageNotificationsResponse response) {
        return new AlertActionResponse(response.alertId(), response.mintId(), response.severity(),
            response.summary(), response.acknowledged(), response.silenced(),
            response.silenceMinutes(), response.escalations() != null ? response.escalations() : List.of(),
            response.message());
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "alert_not_found", e.getMessage());
        }
        if (message.contains("already exists")) {
            return new AdminServiceException(HttpStatus.CONFLICT, "alert_exists", e.getMessage());
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "alert_error", e.getMessage());
    }
}
