package xyz.tcheeric.cashu.mint.admin.rest.dto.operations;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response payload representing the outcome of an operational control action.
 */
@Schema(description = "Result of an operational control action.")
public record OperationalControlResponse(
        @Schema(description = "Identifier of the mint", example = "123e4567-e89b-12d3-a456-426614174000") String mintId,
        @Schema(description = "Identifier of the control action", example = "ctrl-001") String controlId,
        @Schema(description = "Current status of the control", example = "SCHEDULED") String status,
        @Schema(description = "Timestamp when the action was scheduled or executed") Instant scheduledAt,
        @Schema(description = "Message describing the outcome", example = "Maintenance window scheduled") String message
) {
}
