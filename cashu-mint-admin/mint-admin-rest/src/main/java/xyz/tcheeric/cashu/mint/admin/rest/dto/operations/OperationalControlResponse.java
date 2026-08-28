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
        @Schema(description = "Kind of control action", example = "KEY_ROTATION") String controlType,
        @Schema(description = "Current status of the control", example = "SCHEDULED") String status,
        @Schema(description = "Timestamp when the action was scheduled or executed") Instant scheduledAt,
        @Schema(description = "Message describing the outcome", example = "Maintenance window scheduled") String message,
        // What the control actually did, which for a rotation is the keyset that now
        // signs and the ones it replaced. Without it an operator can see that a
        // rotation completed but not which keyset replaced which.
        @Schema(description = "Result recorded once the control completed",
                example = "Keyset 00393773ab373fe8 replaces [00e3372e61d05605]") String outcome
) {
}
