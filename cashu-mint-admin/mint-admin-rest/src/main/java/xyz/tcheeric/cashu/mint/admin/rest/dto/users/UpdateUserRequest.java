package xyz.tcheeric.cashu.mint.admin.rest.dto.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Request payload for updating an operator account.
 */
public record UpdateUserRequest(
        @NotBlank(message = "display name is required") String displayName,
        @Email(message = "email must be valid") String email,
        @NotEmpty(message = "at least one role must be provided") Set<@NotBlank(message = "role must not be blank") String> roles
) {
}
