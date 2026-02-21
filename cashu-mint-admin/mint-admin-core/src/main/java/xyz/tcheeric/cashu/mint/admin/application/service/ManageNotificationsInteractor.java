package xyz.tcheeric.cashu.mint.admin.application.service;

import java.util.List;
import java.util.Map;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository.AlertRecord;

/**
 * Implements {@link ManageNotificationsUseCase} for alert and notification policy management.
 */
public class ManageNotificationsInteractor extends AbstractUseCaseInteractor
    implements ManageNotificationsUseCase {

    private final AlertRepository alertRepository;

    public ManageNotificationsInteractor(final AlertRepository alertRepository) {
        this.alertRepository = java.util.Objects.requireNonNull(alertRepository,
            "alert repository must not be null");
    }

    @Override
    public ManageNotificationsResponse handle(final ManageNotificationsRequest request) {
        final ManageNotificationsRequest validated = requireRequest(request, "manage notifications request");
        if (validated.command() == null) {
            throw new IllegalArgumentException("notification command must not be null");
        }

        return switch (validated.command()) {
            case CREATE_ALERT -> createAlert(validated);
            case ACKNOWLEDGE -> acknowledgeAlert(validated);
            case SILENCE_ALERT -> silenceAlert(validated);
            case UNSILENCE_ALERT -> unsilenceAlert(validated);
            case ESCALATE_ALERT -> escalateAlert(validated);
            case CREATE_POLICY, UPDATE_POLICY, ENABLE_CHANNEL, DISABLE_CHANNEL ->
                handlePolicyCommand(validated);
        };
    }

    private ManageNotificationsResponse createAlert(final ManageNotificationsRequest request) {
        requireNonBlank(request.alertId(), "alert id");
        requireNonBlank(request.mintId(), "mint id");
        requireNonBlank(request.severity(), "severity");
        requireNonBlank(request.summary(), "summary");

        final AlertRecord record = new AlertRecord(request.alertId(), request.mintId(),
            request.severity().toUpperCase(), request.summary(),
            request.labels() == null ? Map.of() : request.labels(),
            false, false, null, List.of());
        final boolean created = alertRepository.create(record);
        if (!created) {
            throw new IllegalStateException("alert already exists: " + request.alertId());
        }
        return toResponse(record, "Alert created");
    }

    private ManageNotificationsResponse acknowledgeAlert(final ManageNotificationsRequest request) {
        final AlertRecord record = requireExistingAlert(request.alertId());
        final AlertRecord updated = new AlertRecord(
            record.alertId(),
            record.mintId(),
            record.severity(),
            record.summary(),
            record.labels(),
            true,
            record.silenced(),
            record.silenceMinutes(),
            record.escalations());
        alertRepository.update(updated);
        return toResponse(updated, "Alert acknowledged");
    }

    private ManageNotificationsResponse silenceAlert(final ManageNotificationsRequest request) {
        final AlertRecord record = requireExistingAlert(request.alertId());
        final AlertRecord updated = new AlertRecord(
            record.alertId(),
            record.mintId(),
            record.severity(),
            record.summary(),
            record.labels(),
            record.acknowledged(),
            true,
            request.silenceMinutes(),
            record.escalations());
        alertRepository.update(updated);
        return toResponse(updated, "Alert silenced for " + request.silenceMinutes() + " minutes");
    }

    private ManageNotificationsResponse unsilenceAlert(final ManageNotificationsRequest request) {
        final AlertRecord record = requireExistingAlert(request.alertId());
        final AlertRecord updated = new AlertRecord(
            record.alertId(),
            record.mintId(),
            record.severity(),
            record.summary(),
            record.labels(),
            record.acknowledged(),
            false,
            null,
            record.escalations());
        alertRepository.update(updated);
        return toResponse(updated, "Alert unsilenced");
    }

    private ManageNotificationsResponse escalateAlert(final ManageNotificationsRequest request) {
        requireNonBlank(request.policyId(), "policy id");
        final AlertRecord record = requireExistingAlert(request.alertId());
        if (!record.escalations().contains(request.policyId())) {
            alertRepository.appendEscalation(record.alertId(), request.policyId());
        }
        final AlertRecord updated = requireExistingAlert(request.alertId());
        return toResponse(updated, "Alert escalated to " + request.policyId());
    }

    private ManageNotificationsResponse handlePolicyCommand(final ManageNotificationsRequest request) {
        validateMintId(request.mintId());
        return new ManageNotificationsResponse(request.policyId(), request.versionTag(),
            null, request.mintId(), null, null, false, false, null, List.of(),
            "Policy " + request.command().name().toLowerCase().replace('_', ' ') + " completed");
    }

    private AlertRecord requireExistingAlert(final String alertId) {
        requireNonBlank(alertId, "alert id");
        return alertRepository.findById(alertId)
            .orElseThrow(() -> new IllegalStateException("alert not found: " + alertId));
    }

    private static String requireNonBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static ManageNotificationsResponse toResponse(final AlertRecord record, final String message) {
        return new ManageNotificationsResponse(null, null, record.alertId(), record.mintId(),
            record.severity(), record.summary(), record.acknowledged(), record.silenced(),
            record.silenceMinutes(), record.escalations(), message);
    }
}
