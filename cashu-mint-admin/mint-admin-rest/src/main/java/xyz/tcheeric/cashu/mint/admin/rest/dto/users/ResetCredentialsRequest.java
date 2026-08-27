package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for initiating a credential reset.
 */
public record ResetCredentialsRequest(
        @NotBlank(message = "reason is required") String reason
) {
}
