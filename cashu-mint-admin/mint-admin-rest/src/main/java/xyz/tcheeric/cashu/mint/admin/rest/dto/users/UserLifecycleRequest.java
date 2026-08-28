package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for activating or deactivating a user.
 */
public record UserLifecycleRequest(
        @NotBlank(message = "reason is required") String reason
) {
}
