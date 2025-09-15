package xyz.tcheeric.cashu.mint.rest.admin.dto.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for activating or deactivating a user.
 */
public record UserLifecycleRequest(
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy,
        @NotBlank(message = "reason is required") String reason
) {
}
