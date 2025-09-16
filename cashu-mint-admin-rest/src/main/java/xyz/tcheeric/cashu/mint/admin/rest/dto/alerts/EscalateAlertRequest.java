package xyz.tcheeric.cashu.mint.admin.rest.dto.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for escalating an alert to an external policy.
 */
public record EscalateAlertRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "policy id is required") String policyId,
        @NotBlank(message = "reason is required") String reason
) {
}
