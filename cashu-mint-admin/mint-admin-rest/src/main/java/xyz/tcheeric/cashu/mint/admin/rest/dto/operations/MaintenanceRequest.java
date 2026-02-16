package xyz.tcheeric.cashu.mint.admin.rest.dto.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for scheduling or managing a maintenance window.
 */
public record MaintenanceRequest(
        String reason,
        Integer durationMinutes,
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy
) {
}
