package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.AlertActionRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.AlertActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.CreateAlertRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.EscalateAlertRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.SilenceAlertRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory alert workflow implementation for REST endpoints.
 */
@Service
public class AdminAlertService {

    private final ConcurrentMap<String, AlertRecord> alerts = new ConcurrentHashMap<>();

    public AlertActionResponse createAlert(final CreateAlertRequest request) {
        Objects.requireNonNull(request, "request");
        final AlertRecord record = new AlertRecord(request.alertId(), request.mintId(),
                request.severity(), request.summary(), request.labels());
        final AlertRecord existing = alerts.putIfAbsent(record.alertId, record);
        if (existing != null) {
            throw new AdminServiceException(HttpStatus.CONFLICT, "alert_exists", "Alert already exists: " + request.alertId());
        }
        return record.toResponse("Alert created");
    }

    public AlertActionResponse acknowledgeAlert(final String alertId, final AlertActionRequest request) {
        Objects.requireNonNull(request, "request");
        final AlertRecord record = alerts.computeIfPresent(alertId, (id, current) -> {
            current.acknowledged = true;
            return current;
        });
        if (record == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found: " + alertId);
        }
        return record.toResponse("Alert acknowledged");
    }

    public AlertActionResponse silenceAlert(final String alertId, final SilenceAlertRequest request) {
        Objects.requireNonNull(request, "request");
        final AlertRecord record = alerts.computeIfPresent(alertId, (id, current) -> {
            current.silenced = true;
            current.silenceMinutes = request.durationMinutes();
            return current;
        });
        if (record == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found: " + alertId);
        }
        return record.toResponse("Alert silenced for " + request.durationMinutes() + " minutes");
    }

    public AlertActionResponse unsilenceAlert(final String alertId, final AlertActionRequest request) {
        Objects.requireNonNull(request, "request");
        final AlertRecord record = alerts.computeIfPresent(alertId, (id, current) -> {
            current.silenced = false;
            current.silenceMinutes = null;
            return current;
        });
        if (record == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found: " + alertId);
        }
        return record.toResponse("Alert unsilenced");
    }

    public AlertActionResponse escalateAlert(final String alertId, final EscalateAlertRequest request) {
        Objects.requireNonNull(request, "request");
        final AlertRecord record = alerts.computeIfPresent(alertId, (id, current) -> {
            if (!current.escalations.contains(request.policyId())) {
                current.escalations.add(request.policyId());
            }
            return current;
        });
        if (record == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found: " + alertId);
        }
        return record.toResponse("Alert escalated to " + request.policyId());
    }

    private static final class AlertRecord {
        private final String alertId;
        private final String mintId;
        private final String severity;
        private final String summary;
        private final Map<String, Object> labels;
        private boolean acknowledged;
        private boolean silenced;
        private Integer silenceMinutes;
        private final List<String> escalations = new ArrayList<>();

        private AlertRecord(final String alertId,
                            final String mintId,
                            final String severity,
                            final String summary,
                            final Map<String, Object> labels) {
            this.alertId = Objects.requireNonNull(alertId, "alertId");
            this.mintId = Objects.requireNonNull(mintId, "mintId");
            this.severity = Objects.requireNonNull(severity, "severity").toUpperCase();
            this.summary = Objects.requireNonNull(summary, "summary");
            this.labels = labels == null ? Map.of() : Map.copyOf(labels);
        }

        private AlertActionResponse toResponse(final String message) {
            return new AlertActionResponse(alertId, mintId, severity, summary,
                    acknowledged, silenced, silenceMinutes,
                    List.copyOf(escalations), message);
        }
    }
}
