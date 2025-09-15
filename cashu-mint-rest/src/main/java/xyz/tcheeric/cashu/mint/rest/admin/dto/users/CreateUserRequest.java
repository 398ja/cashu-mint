package xyz.tcheeric.cashu.mint.rest.admin.dto.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;

/**
 * Request payload for provisioning a new operator account.
 */
public record CreateUserRequest(
        @NotBlank(message = "user id is required") String userId,
        @NotBlank(message = "display name is required") String displayName,
        @Email(message = "email must be valid") String email,
        @NotEmpty(message = "at least one role must be provided") Set<@NotBlank(message = "role must not be blank") String> roles,
        @NotNull(message = "requestedBy is required") @Valid ActorDto requestedBy
) {
}
