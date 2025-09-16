package xyz.tcheeric.cashu.mint.admin.rest.dto.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for declaring a new operational alert.
 */
public record CreateAlertRequest(
        @NotBlank(message = "alert id is required") String alertId,
        @NotBlank(message = "mint id is required") String mintId,
        @NotBlank(message = "severity is required") String severity,
        @NotBlank(message = "summary is required") String summary,
        Map<String, Object> labels,
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy
) {
}
