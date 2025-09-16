package xyz.tcheeric.cashu.mint.admin.rest.dto.alerts;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response payload representing the current status of an alert.
 */
@Schema(description = "Representation of an alert after performing an administrative action.")
public record AlertActionResponse(
        @Schema(description = "Identifier of the alert", example = "alert-1") String alertId,
        @Schema(description = "Identifier of the mint that raised the alert", example = "mint-001") String mintId,
        @Schema(description = "Severity level of the alert", example = "CRITICAL") String severity,
        @Schema(description = "Human readable summary of the alert", example = "Mint offline") String summary,
        @Schema(description = "Whether the alert has been acknowledged", example = "true") boolean acknowledged,
        @Schema(description = "Whether notifications are currently silenced", example = "false") boolean silenced,
        @Schema(description = "Minutes remaining for the silence window if active", example = "30", nullable = true) Integer silenceMinutes,
        @Schema(description = "Escalation policy identifiers applied to the alert", example = "[\"pagerduty\"]") List<String> escalations,
        @Schema(description = "Message describing the action outcome", example = "Alert escalated to pagerduty") String message
) {
}
