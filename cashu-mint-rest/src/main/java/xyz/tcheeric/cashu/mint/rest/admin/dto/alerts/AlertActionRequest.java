package xyz.tcheeric.cashu.mint.rest.admin.dto.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for acknowledgement or unsilence operations on an alert.
 */
public record AlertActionRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "reason is required") String reason,
        String comment
) {
}
