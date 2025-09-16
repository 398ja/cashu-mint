package xyz.tcheeric.cashu.mint.admin.rest.dto.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;

/**
 * Request payload for acknowledgement or unsilence operations on an alert.
 */
public record AlertActionRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "reason is required") String reason,
        String comment
) {
}
