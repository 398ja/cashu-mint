package xyz.tcheeric.cashu.mint.admin.rest.dto.common;

import jakarta.validation.constraints.NotBlank;

/**
 * Identifies the operator initiating an administrative request.
 */
public record ActorDto(
        @NotBlank(message = "actor id is required") String id,
        @NotBlank(message = "actor display name is required") String displayName
) {
}
