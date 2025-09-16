package xyz.tcheeric.cashu.mint.admin.rest.dto.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for silencing an alert.
 */
public record SilenceAlertRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "reason is required") String reason,
        @Min(value = 1, message = "durationMinutes must be at least 1") int durationMinutes
) {
}
