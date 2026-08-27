package xyz.tcheeric.cashu.mint.admin.rest.dto.operations;

/**
 * Request payload for scheduling or managing a maintenance window.
 */
public record MaintenanceRequest(
        String reason,
        Integer durationMinutes
) {
}
